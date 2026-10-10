package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.BlobStore
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.UUID

/** 附件分片上传会话管理：创建 / 状态查询 / 分块续传。 */
internal fun Route.configureEncryptedAttachmentUploadSessionRoutes(
    userRepo: UserRepository,
    encryptedAttachmentRepo: EncryptedAttachmentRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    rateLimiter: BoundedRateLimiter,
) {
    authenticate("auth-jwt") {

        post("/api/attachment-uploads") {
            val userId = call.requireUserId()
            if (call.rejectIfMessageRestricted(userRepo, userId)) return@post
            val request = call.receiveJson<AttachmentUploadSessionRequest>()
            if (
                request == null ||
                request.chatId.isBlank() ||
                !CLIENT_MESSAGE_ID_REGEX.matches(request.messageId) ||
                !request.cipherSha256.lowercase().matches(sha256HexRegex) ||
                request.cipherSize !in 17L..MAX_ATTACHMENT_CIPHER_BYTES
            ) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("附件上传会话参数无效"))
                return@post
            }
            if (!conversationParticipantRepo.isParticipant(request.chatId, userId)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权向该聊天上传附件"))
                return@post
            }
            if (conversationQueryRepo.getById(request.chatId)?.isGroup == true && conversationParticipantRepo.isMuted(request.chatId, userId)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("你已被禁言，暂时无法上传附件"))
                return@post
            }
            if (!encryptedAttachmentRepo.hasCapacityFor(userId, request.chatId, request.messageId, request.cipherSize, maxAttachmentUserBytes)) {
                call.respond(ATTACHMENT_QUOTA_STATUS, ErrorResponse("附件存储配额不足"))
                return@post
            }
            if (!rateLimiter.acquire("attachment_session:$userId", maxPerMinute = 40)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("附件上传过于频繁"))
                return@post
            }
            val attachmentId = "att_${UUID.randomUUID().toString().replace("-", "")}" 
            val expiresAt = System.currentTimeMillis() + ATTACHMENT_UPLOAD_TTL_MS
            val created = runCatching {
                encryptedAttachmentRepo.createUploadSession(
                    id = attachmentId,
                    chatId = request.chatId,
                    uploaderId = userId,
                    pendingMessageId = request.messageId,
                    sha256 = request.cipherSha256.lowercase(),
                    cipherSize = request.cipherSize,
                    expiresAt = expiresAt,
                    maxUserBytes = maxAttachmentUserBytes
                )
            }
            val session = created.getOrElse { error ->
                when (error) {
                    is AttachmentQuotaExceededException -> call.respond(ATTACHMENT_QUOTA_STATUS, ErrorResponse("附件存储配额不足"))
                    is AttachmentMessageAlreadyUsedException -> call.respond(HttpStatusCode.Conflict, ErrorResponse("消息 ID 已被使用"))
                    is AttachmentNotAllowedException -> {
                        val msg = when (error.message) {
                            "muted" -> "你已被禁言，暂时无法上传附件"
                            "not_participant", "chat_not_found" -> "无权向该聊天上传附件"
                            else -> "无权向该聊天上传附件"
                        }
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse(msg))
                    }
                    else -> {
                        call.application.log.warn("Encrypted attachment session creation failed", error)
                        call.respond(HttpStatusCode.InternalServerError, ErrorResponse("附件上传会话创建失败"))
                    }
                }
                return@post
            }
            session.replacedIds.forEach(BlobStore::delete)
            val refreshed = reconcileAttachmentUpload(session.record, encryptedAttachmentRepo, userId)
            if (refreshed == null) {
                encryptedAttachmentRepo.removeUncommitted(session.record.id, userId)
                BlobStore.delete(session.record.id)
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("附件总哈希校验失败"))
                return@post
            }
            call.respond(
                if (session.reused) HttpStatusCode.OK else HttpStatusCode.Created,
                refreshed.toUploadStatus()
            )
        }



        get("/api/attachment-uploads/{id}") {
            val userId = call.requireUserId()
            val attachmentId = parseRawOrEmpty(call.parameters, "id")
            val record = encryptedAttachmentRepo.get(attachmentId)
            if (record == null || record.uploaderId != userId) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("附件上传会话不存在"))
                return@get
            }
            if (!conversationParticipantRepo.isParticipant(record.chatId, userId)) {
                encryptedAttachmentRepo.removeUncommitted(attachmentId, userId)
                // 9.151：已 COMMITTED 的附件密文仍被群内其他成员下载，
                // 上传者退群后重查状态/重传不得连带删除 .bin
                if (record.status != AttachmentStatus.COMMITTED.dbValue) BlobStore.delete(attachmentId)
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("已不在该聊天中"))
                return@get
            }
            if (record.status == AttachmentStatus.COMMITTED.dbValue) {
                call.respond(record.toUploadStatus())
                return@get
            }
            if (record.expiresAt != null && record.expiresAt <= System.currentTimeMillis()) {
                encryptedAttachmentRepo.removeUncommitted(attachmentId, userId)
                BlobStore.delete(attachmentId)
                call.respond(HttpStatusCode.Gone, ErrorResponse("附件上传会话已过期"))
                return@get
            }
            val reconciled = reconcileAttachmentUpload(record, encryptedAttachmentRepo, userId)
            if (reconciled == null) {
                BlobStore.delete(attachmentId)
                encryptedAttachmentRepo.removeUncommitted(attachmentId, userId)
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("附件总哈希校验失败"))
                return@get
            }
            call.respond(reconciled.toUploadStatus())
        }



        put("/api/attachment-uploads/{id}") {
            val userId = call.requireUserId()
            if (call.rejectIfMessageRestricted(userRepo, userId)) return@put
            val attachmentId = parseRawOrEmpty(call.parameters, "id")
            val offset = parseOptionalLong(call.request.queryParameters, "offset")
            val chunkHash = call.request.header(ATTACHMENT_CHUNK_HASH_HEADER)?.lowercase().orEmpty()
            val declaredLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
            val record = encryptedAttachmentRepo.get(attachmentId)
            if (record == null || record.uploaderId != userId) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("附件上传会话不存在"))
                return@put
            }
            if (!conversationParticipantRepo.isParticipant(record.chatId, userId)) {
                encryptedAttachmentRepo.removeUncommitted(attachmentId, userId)
                // 9.151：同 GET——COMMITTED 附件密文不可因上传者退群后的重传被删除
                if (record.status != AttachmentStatus.COMMITTED.dbValue) BlobStore.delete(attachmentId)
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("已不在该聊天中"))
                return@put
            }
            if (conversationQueryRepo.getById(record.chatId)?.isGroup == true && conversationParticipantRepo.isMuted(record.chatId, userId)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("你已被禁言，暂时无法上传附件"))
                return@put
            }
            if (record.expiresAt != null && record.expiresAt <= System.currentTimeMillis()) {
                encryptedAttachmentRepo.removeUncommitted(attachmentId, userId)
                BlobStore.delete(attachmentId)
                call.respond(HttpStatusCode.Gone, ErrorResponse("附件上传会话已过期"))
                return@put
            }
            if (!rateLimiter.acquire("attachment_chunk:$userId", maxPerMinute = 180)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("附件分块上传过于频繁"))
                return@put
            }
            if (record.status != AttachmentStatus.UPLOADING.dbValue) {
                call.respond(record.toUploadStatus())
                return@put
            }
            if (
                offset == null ||
                declaredLength == null || declaredLength !in 1L..MAX_ATTACHMENT_CHUNK_BYTES ||
                offset < 0L || offset + declaredLength > record.cipherSize ||
                !chunkHash.matches(sha256HexRegex) ||
                call.request.contentType().withoutParameters() != ContentType.Application.OctetStream
            ) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("附件分块参数无效"))
                return@put
            }
            val chunk = call.receiveEncryptedAttachmentChunk(MAX_ATTACHMENT_CHUNK_BYTES.toInt())
            if (chunk == null || chunk.size.toLong() != declaredLength || chunk.sha256Hex() != chunkHash) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("附件分块长度或哈希无效"))
                return@put
            }
            when (val appended = withContext(Dispatchers.IO) {
                BlobStore.appendChunk(attachmentId, offset, chunk, record.cipherSize)
            }) {
                is BlobStore.AppendResult.OffsetMismatch -> {
                    call.respond(HttpStatusCode.Conflict, record.toUploadStatus(appended.uploadedBytes))
                }
                BlobStore.AppendResult.ContentMismatch -> {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("附件分块与已上传内容冲突"))
                }
                is BlobStore.AppendResult.Accepted -> {
                    if (!encryptedAttachmentRepo.updateUploadProgress(attachmentId, userId, appended.uploadedBytes)) {
                        encryptedAttachmentRepo.removeUncommitted(attachmentId, userId)
                        BlobStore.delete(attachmentId)
                        call.respond(HttpStatusCode.Conflict, ErrorResponse("附件上传会话已被替换"))
                        return@put
                    }
                    if (appended.uploadedBytes == record.cipherSize) {
                        if (withContext(Dispatchers.IO) { BlobStore.sha256(attachmentId) } != record.cipherSha256) {
                            encryptedAttachmentRepo.removeUncommitted(attachmentId, userId)
                            BlobStore.delete(attachmentId)
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("附件总哈希校验失败"))
                            return@put
                        }
                        val finalized = withContext(Dispatchers.IO) {
                            BlobStore.finalizeResumableUpload(attachmentId)
                        }
                        if (finalized == null || !encryptedAttachmentRepo.markUploaded(attachmentId, userId)) {
                            encryptedAttachmentRepo.removeUncommitted(attachmentId, userId)
                            BlobStore.delete(attachmentId)
                            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("附件完成状态保存失败"))
                            return@put
                        }
                    }
                    val updated = encryptedAttachmentRepo.get(attachmentId) ?: record.copy(uploadedBytes = appended.uploadedBytes)
                    call.respond(updated.toUploadStatus(appended.uploadedBytes))
                }
            }
        }
    }
}

private fun EncryptedAttachmentRecord.toUploadStatus(uploadedBytesOverride: Long? = null): AttachmentUploadStatusResponse {
    val actualBytes = uploadedBytesOverride ?: if (status == AttachmentStatus.UPLOADING.dbValue) uploadedBytes else cipherSize
    return AttachmentUploadStatusResponse(
        id = id,
        cipherSha256 = cipherSha256,
        cipherSize = cipherSize,
        uploadedBytes = actualBytes.coerceIn(0L, cipherSize),
        status = status,
        expiresAt = expiresAt ?: 0L,
        complete = status == AttachmentStatus.UPLOADED.dbValue || status == AttachmentStatus.COMMITTED.dbValue
    )
}
private suspend fun reconcileAttachmentUpload(
    record: EncryptedAttachmentRecord,
    repository: EncryptedAttachmentRepository,
    userId: String
): EncryptedAttachmentRecord? {
    if (record.status != AttachmentStatus.UPLOADING.dbValue) return record
    val actualBytes = withContext(Dispatchers.IO) { BlobStore.uploadedBytes(record.id) }
        ?.coerceAtMost(record.cipherSize) ?: 0L
    if (actualBytes < record.uploadedBytes) return null
    if (!repository.updateUploadProgress(record.id, userId, actualBytes)) return null
    if (actualBytes < record.cipherSize) return repository.get(record.id)?.copy(uploadedBytes = actualBytes)
    if (withContext(Dispatchers.IO) { BlobStore.sha256(record.id) } != record.cipherSha256) return null
    if (withContext(Dispatchers.IO) { BlobStore.finalizeResumableUpload(record.id) } == null) return null
    if (!repository.markUploaded(record.id, userId)) return null
    return repository.get(record.id)
}
private suspend fun ApplicationCall.receiveEncryptedAttachmentChunk(maxBytes: Int): ByteArray? {
    val channel = receiveChannel()
    val output = java.io.ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read < 0) break
        if (read == 0) {
            // 9.150：同上——等待数据/EOF，避免空转烧 CPU
            channel.awaitContent()
            continue
        }
        if (output.size() + read > maxBytes) return null
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
