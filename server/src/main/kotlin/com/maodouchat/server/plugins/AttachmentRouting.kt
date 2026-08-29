package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.EncryptedAttachmentStorage
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
import java.security.MessageDigest
import java.util.UUID

internal fun Route.configureEncryptedAttachmentRoutes(
    userRepo: UserRepository,
    encryptedAttachmentRepo: EncryptedAttachmentRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    rateLimiter: BoundedRateLimiter,
) {
    authenticate("auth-jwt") {
            post("/api/attachment-uploads") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (call.rejectIfMessageRestricted(userRepo, userId)) return@post
                val request = call.receiveBoundedText()?.let { parseJson<AttachmentUploadSessionRequest>(it) }
                if (
                    request == null ||
                    request.chatId.isBlank() ||
                    !CLIENT_MESSAGE_ID_REGEX.matches(request.messageId) ||
                    !request.cipherSha256.lowercase().matches(Regex("^[a-f0-9]{64}$")) ||
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
                session.replacedIds.forEach(EncryptedAttachmentStorage::delete)
                val refreshed = reconcileAttachmentUpload(session.record, encryptedAttachmentRepo, userId)
                if (refreshed == null) {
                    encryptedAttachmentRepo.removeUncommitted(session.record.id, userId)
                    EncryptedAttachmentStorage.delete(session.record.id)
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("附件总哈希校验失败"))
                    return@post
                }
                call.respond(
                    if (session.reused) HttpStatusCode.OK else HttpStatusCode.Created,
                    refreshed.toUploadStatus()
                )
            }

            get("/api/attachment-uploads/{id}") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val attachmentId = call.parameters["id"].orEmpty()
                val record = encryptedAttachmentRepo.get(attachmentId)
                if (record == null || record.uploaderId != userId) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("附件上传会话不存在"))
                    return@get
                }
                if (!conversationParticipantRepo.isParticipant(record.chatId, userId)) {
                    encryptedAttachmentRepo.removeUncommitted(attachmentId, userId)
                    // 9.151：已 COMMITTED 的附件密文仍被群内其他成员下载，
                    // 上传者退群后重查状态/重传不得连带删除 .bin
                    if (record.status != "COMMITTED") EncryptedAttachmentStorage.delete(attachmentId)
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("已不在该聊天中"))
                    return@get
                }
                if (record.status == "COMMITTED") {
                    call.respond(record.toUploadStatus())
                    return@get
                }
                if (record.expiresAt != null && record.expiresAt <= System.currentTimeMillis()) {
                    encryptedAttachmentRepo.removeUncommitted(attachmentId, userId)
                    EncryptedAttachmentStorage.delete(attachmentId)
                    call.respond(HttpStatusCode.Gone, ErrorResponse("附件上传会话已过期"))
                    return@get
                }
                val reconciled = reconcileAttachmentUpload(record, encryptedAttachmentRepo, userId)
                if (reconciled == null) {
                    EncryptedAttachmentStorage.delete(attachmentId)
                    encryptedAttachmentRepo.removeUncommitted(attachmentId, userId)
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("附件总哈希校验失败"))
                    return@get
                }
                call.respond(reconciled.toUploadStatus())
            }

            put("/api/attachment-uploads/{id}") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (call.rejectIfMessageRestricted(userRepo, userId)) return@put
                val attachmentId = call.parameters["id"].orEmpty()
                val offset = call.request.queryParameters["offset"]?.toLongOrNull()
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
                    if (record.status != "COMMITTED") EncryptedAttachmentStorage.delete(attachmentId)
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("已不在该聊天中"))
                    return@put
                }
                if (conversationQueryRepo.getById(record.chatId)?.isGroup == true && conversationParticipantRepo.isMuted(record.chatId, userId)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("你已被禁言，暂时无法上传附件"))
                    return@put
                }
                if (record.expiresAt != null && record.expiresAt <= System.currentTimeMillis()) {
                    encryptedAttachmentRepo.removeUncommitted(attachmentId, userId)
                    EncryptedAttachmentStorage.delete(attachmentId)
                    call.respond(HttpStatusCode.Gone, ErrorResponse("附件上传会话已过期"))
                    return@put
                }
                if (!rateLimiter.acquire("attachment_chunk:$userId", maxPerMinute = 180)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("附件分块上传过于频繁"))
                    return@put
                }
                if (record.status != "UPLOADING") {
                    call.respond(record.toUploadStatus())
                    return@put
                }
                if (
                    offset == null ||
                    declaredLength == null || declaredLength !in 1L..MAX_ATTACHMENT_CHUNK_BYTES ||
                    offset < 0L || offset + declaredLength > record.cipherSize ||
                    !chunkHash.matches(Regex("^[a-f0-9]{64}$")) ||
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
                    EncryptedAttachmentStorage.appendChunk(attachmentId, offset, chunk, record.cipherSize)
                }) {
                    is EncryptedAttachmentStorage.AppendResult.OffsetMismatch -> {
                        call.respond(HttpStatusCode.Conflict, record.toUploadStatus(appended.uploadedBytes))
                    }
                    EncryptedAttachmentStorage.AppendResult.ContentMismatch -> {
                        call.respond(HttpStatusCode.Conflict, ErrorResponse("附件分块与已上传内容冲突"))
                    }
                    is EncryptedAttachmentStorage.AppendResult.Accepted -> {
                        if (!encryptedAttachmentRepo.updateUploadProgress(attachmentId, userId, appended.uploadedBytes)) {
                            encryptedAttachmentRepo.removeUncommitted(attachmentId, userId)
                            EncryptedAttachmentStorage.delete(attachmentId)
                            call.respond(HttpStatusCode.Conflict, ErrorResponse("附件上传会话已被替换"))
                            return@put
                        }
                        if (appended.uploadedBytes == record.cipherSize) {
                            if (withContext(Dispatchers.IO) { EncryptedAttachmentStorage.sha256(attachmentId) } != record.cipherSha256) {
                                encryptedAttachmentRepo.removeUncommitted(attachmentId, userId)
                                EncryptedAttachmentStorage.delete(attachmentId)
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("附件总哈希校验失败"))
                                return@put
                            }
                            val finalized = withContext(Dispatchers.IO) {
                                EncryptedAttachmentStorage.finalizeResumableUpload(attachmentId)
                            }
                            if (finalized == null || !encryptedAttachmentRepo.markUploaded(attachmentId, userId)) {
                                encryptedAttachmentRepo.removeUncommitted(attachmentId, userId)
                                EncryptedAttachmentStorage.delete(attachmentId)
                                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("附件完成状态保存失败"))
                                return@put
                            }
                        }
                        val updated = encryptedAttachmentRepo.get(attachmentId) ?: record.copy(uploadedBytes = appended.uploadedBytes)
                        call.respond(updated.toUploadStatus(appended.uploadedBytes))
                    }
                }
            }

            post("/api/attachments") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (call.rejectIfMessageRestricted(userRepo, userId)) return@post
                val chatId = call.request.queryParameters["chatId"].orEmpty()
                val pendingMessageId = call.request.queryParameters["messageId"].orEmpty()
                val expectedHash = call.request.header(ATTACHMENT_HASH_HEADER)?.lowercase().orEmpty()
                val declaredLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
                if (chatId.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("聊天 ID 无效"))
                    return@post
                }
                if (!conversationParticipantRepo.isParticipant(chatId, userId)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权向该聊天上传附件"))
                    return@post
                }
                if (conversationQueryRepo.getById(chatId)?.isGroup == true && conversationParticipantRepo.isMuted(chatId, userId)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("你已被禁言，暂时无法上传附件"))
                    return@post
                }
                if (!CLIENT_MESSAGE_ID_REGEX.matches(pendingMessageId)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("附件消息 ID 无效"))
                    return@post
                }
                if (call.request.contentType().withoutParameters() != ContentType.Application.OctetStream) {
                    call.respond(HttpStatusCode.UnsupportedMediaType, ErrorResponse("附件必须使用二进制上传"))
                    return@post
                }
                if (!expectedHash.matches(Regex("^[a-f0-9]{64}$"))) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("附件哈希无效"))
                    return@post
                }
                if (declaredLength == null || declaredLength !in 17L..MAX_ATTACHMENT_CIPHER_BYTES) {
                    call.respond(ATTACHMENT_TOO_LARGE_STATUS, ErrorResponse("附件大小无效或超过限制"))
                    return@post
                }
                if (!encryptedAttachmentRepo.hasCapacityFor(userId, chatId, pendingMessageId, declaredLength, maxAttachmentUserBytes)) {
                    call.respond(ATTACHMENT_QUOTA_STATUS, ErrorResponse("附件存储配额不足"))
                    return@post
                }
                if (!rateLimiter.acquire("attachment_upload:$userId", maxPerMinute = 20)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("附件上传过于频繁"))
                    return@post
                }
                val attachmentId = "att_${UUID.randomUUID().toString().replace("-", "")}" 
                val tempFile = EncryptedAttachmentStorage.createTempFile(attachmentId)
                val received = try {
                    call.receiveEncryptedAttachment(tempFile, MAX_ATTACHMENT_CIPHER_BYTES)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    EncryptedAttachmentStorage.delete(attachmentId)
                    throw cancelled
                } catch (error: Throwable) {
                    EncryptedAttachmentStorage.delete(attachmentId)
                    call.application.log.warn("Encrypted attachment receive failed", error)
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("附件上传中断"))
                    return@post
                }
                if (received == null || received.byteCount != declaredLength || received.sha256 != expectedHash) {
                    EncryptedAttachmentStorage.delete(attachmentId)
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("附件长度或哈希校验失败"))
                    return@post
                }
                val expiresAt = System.currentTimeMillis() + ATTACHMENT_UPLOAD_TTL_MS
                val stored = runCatching {
                    EncryptedAttachmentStorage.finalizeUpload(attachmentId, tempFile)
                    encryptedAttachmentRepo.createReplacingPending(
                        id = attachmentId,
                        chatId = chatId,
                        uploaderId = userId,
                        pendingMessageId = pendingMessageId,
                        sha256 = received.sha256,
                        cipherSize = received.byteCount,
                        expiresAt = expiresAt,
                        maxUserBytes = maxAttachmentUserBytes
                    )
                }
                if (stored.isFailure) {
                    EncryptedAttachmentStorage.delete(attachmentId)
                    when (val error = stored.exceptionOrNull()) {
                        is AttachmentQuotaExceededException -> call.respond(ATTACHMENT_QUOTA_STATUS, ErrorResponse("附件存储配额不足"))
                        is AttachmentMessageAlreadyUsedException -> call.respond(HttpStatusCode.Conflict, ErrorResponse("消息 ID 已被使用"))
                        is AttachmentNotAllowedException -> call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权向该聊天上传附件"))
                        else -> {
                            call.application.log.warn("Encrypted attachment upload failed", error)
                            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("附件保存失败"))
                        }
                    }
                    return@post
                }
                stored.getOrThrow().forEach(EncryptedAttachmentStorage::delete)
                call.respond(
                    HttpStatusCode.Created,
                    AttachmentUploadResponse(attachmentId, received.sha256, received.byteCount, expiresAt)
                )
            }

            get("/api/attachments/{id}") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                // Bandwidth / bulk-exfil throttle (authenticated participants still rate-limited)
                if (!rateLimiter.acquire("attachment_download:$userId", maxPerMinute = 60)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("附件下载过于频繁，请稍后再试"))
                    return@get
                }
                val attachmentId = call.parameters["id"].orEmpty()
                val record = encryptedAttachmentRepo.get(attachmentId) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("附件不存在"))
                    return@get
                }
                if (record.expiresAt != null && record.expiresAt <= System.currentTimeMillis()) {
                    encryptedAttachmentRepo.removeUncommitted(attachmentId, record.uploaderId)
                    EncryptedAttachmentStorage.delete(attachmentId)
                    call.respond(HttpStatusCode.Gone, ErrorResponse("附件已过期"))
                    return@get
                }
                if (record.status != "COMMITTED") {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("附件尚未关联消息"))
                    return@get
                }
                if (!encryptedAttachmentRepo.isBoundToLiveMessage(attachmentId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("附件关联消息不存在"))
                    return@get
                }
                if (!conversationParticipantRepo.isParticipant(record.chatId, userId)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权下载该附件"))
                    return@get
                }
                // 与历史消息一致：双向拉黑语义（8.30 隐私修复）——viewer 拉黑了发送者，
                // 或发送者拉黑了 viewer，都不可下载其附件密文。
                val boundMessageId = record.messageId
                if (!boundMessageId.isNullOrBlank()) {
                    val senderId = com.maodouchat.server.messaging.v2.MessagingV2Repository()
                        .messageMetadata(boundMessageId)
                        ?.senderUserId
                    if (senderId != null && senderId != userId && userRepo.isBlockedEitherWay(userId, senderId)) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权下载该附件"))
                        return@get
                    }
                }
                val file = EncryptedAttachmentStorage.resolve(attachmentId)
                if (file == null || file.length() != record.cipherSize) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("附件密文不可用"))
                    return@get
                }
                call.response.header(ATTACHMENT_HASH_HEADER, record.cipherSha256)
                call.response.header(HttpHeaders.CacheControl, "private, no-store")
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=encrypted-attachment.bin")
                call.response.header("Accept-Ranges", "bytes")
                val rangeHeader = call.request.header("Range")
                // 9.151：多区段（含逗号）忽略回退全量（RFC 允许）；单区段非法/无法满足 → 416
                val range = rangeHeader?.takeIf { ',' !in it }?.let { parseAttachmentRange(it, file.length()) }
                if (rangeHeader != null && ',' !in rangeHeader && range == null) {
                    call.response.header("Content-Range", "bytes */${file.length()}")
                    call.respondText("", status = ATTACHMENT_RANGE_NOT_SATISFIABLE)
                    return@get
                }
                if (range == null) {
                    call.respondFile(file)
                } else {
                    val remaining = range.last - range.first + 1
                    call.response.header("Content-Range", "bytes ${range.first}-${range.last}/${file.length()}")
                    call.response.header(HttpHeaders.ContentLength, remaining)
                    call.respondOutputStream(
                        contentType = ContentType.Application.OctetStream,
                        status = HttpStatusCode.PartialContent
                    ) {
                        java.io.RandomAccessFile(file, "r").use { input ->
                            input.seek(range.first)
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var left = remaining
                            while (left > 0L) {
                                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                                if (read < 0) break
                                write(buffer, 0, read)
                                left -= read
                            }
                        }
                    }
                }
            }

            delete("/api/attachments/{id}") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val attachmentId = call.parameters["id"].orEmpty()
                if (!encryptedAttachmentRepo.removeUncommitted(attachmentId, userId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("待确认附件不存在"))
                    return@delete
                }
                EncryptedAttachmentStorage.delete(attachmentId)
                call.respond(
                buildJsonObject {
put("status", "ok")
                }
            )
            }
    }
}

private data class ReceivedEncryptedAttachment(val byteCount: Long, val sha256: String)

private fun EncryptedAttachmentRecord.toUploadStatus(uploadedBytesOverride: Long? = null): AttachmentUploadStatusResponse {
    val actualBytes = uploadedBytesOverride ?: if (status == "UPLOADING") uploadedBytes else cipherSize
    return AttachmentUploadStatusResponse(
        id = id,
        cipherSha256 = cipherSha256,
        cipherSize = cipherSize,
        uploadedBytes = actualBytes.coerceIn(0L, cipherSize),
        status = status,
        expiresAt = expiresAt ?: 0L,
        complete = status == "UPLOADED" || status == "COMMITTED"
    )
}

private suspend fun reconcileAttachmentUpload(
    record: EncryptedAttachmentRecord,
    repository: EncryptedAttachmentRepository,
    userId: String
): EncryptedAttachmentRecord? {
    if (record.status != "UPLOADING") return record
    val actualBytes = withContext(Dispatchers.IO) { EncryptedAttachmentStorage.uploadedBytes(record.id) }
        ?.coerceAtMost(record.cipherSize) ?: 0L
    if (actualBytes < record.uploadedBytes) return null
    if (!repository.updateUploadProgress(record.id, userId, actualBytes)) return null
    if (actualBytes < record.cipherSize) return repository.get(record.id)?.copy(uploadedBytes = actualBytes)
    if (withContext(Dispatchers.IO) { EncryptedAttachmentStorage.sha256(record.id) } != record.cipherSha256) return null
    if (withContext(Dispatchers.IO) { EncryptedAttachmentStorage.finalizeResumableUpload(record.id) } == null) return null
    if (!repository.markUploaded(record.id, userId)) return null
    return repository.get(record.id)
}

// 9.151：支持 bytes=a-b / bytes=a- / bytes=-n 三种单区段形式（RFC 9110）。
// 非法或满足不了的单区段返回 null（→ 416）；多区段（含逗号）由调用方选择忽略回退全量。
private fun parseAttachmentRange(value: String, fileSize: Long): LongRange? {
    if (fileSize <= 0L) return null
    val trimmed = value.trim()
    Regex("^bytes=(\\d+)-(\\d*)$").matchEntire(trimmed)?.let { m ->
        val start = m.groupValues[1].toLongOrNull() ?: return null
        if (start >= fileSize) return null
        val endRaw = m.groupValues[2]
        val end = if (endRaw.isEmpty()) fileSize - 1 else (endRaw.toLongOrNull() ?: return null).coerceAtMost(fileSize - 1)
        return if (start <= end) start..end else null
    }
    Regex("^bytes=-(\\d+)$").matchEntire(trimmed)?.let { m ->
        val length = m.groupValues[1].toLongOrNull() ?: return null
        if (length <= 0L) return null
        return (fileSize - length).coerceAtLeast(0L)..(fileSize - 1)
    }
    return null
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

private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { "%02x".format(it) }

private suspend fun ApplicationCall.receiveEncryptedAttachment(
    target: java.io.File,
    maxBytes: Long
): ReceivedEncryptedAttachment? {
    val digest = MessageDigest.getInstance("SHA-256")
    val channel = receiveChannel()
    var total = 0L
    return try {
        target.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read < 0) break
                if (read == 0) {
                    // 9.150：同上——慢速客户端逐字节上传时等待数据，避免空转烧 CPU
                    channel.awaitContent()
                    continue
                }
                total += read
                if (total > maxBytes) return null
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
        }
        if (total < 17L) null else ReceivedEncryptedAttachment(
            byteCount = total,
            sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        )
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        target.delete()
        throw cancel
    } catch (_: Exception) {
        target.delete()
        null
    }
}
