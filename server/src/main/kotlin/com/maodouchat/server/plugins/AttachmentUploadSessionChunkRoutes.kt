package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.BlobStore
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 附件分片上传会话：分块续传（PUT /api/attachment-uploads/{id}）。 */
internal fun Route.configureAttachmentUploadSessionChunkRoutes(
    userRepo: UserRepository,
    encryptedAttachmentRepo: EncryptedAttachmentRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    rateLimiter: BoundedRateLimiter,
) {
    authenticate("auth-jwt") {



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
