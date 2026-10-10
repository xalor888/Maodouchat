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
import java.util.UUID

/** 附件直传：二进制上传（POST /api/attachments）。 */
internal fun Route.configureAttachmentDirectUploadRoutes(
    userRepo: UserRepository,
    encryptedAttachmentRepo: EncryptedAttachmentRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    rateLimiter: BoundedRateLimiter,
) {
    authenticate("auth-jwt") {


        post("/api/attachments") {
            val userId = call.requireUserId()
            if (call.rejectIfMessageRestricted(userRepo, userId)) return@post
            val chatId = parseRawOrEmpty(call.request.queryParameters, "chatId")
            val pendingMessageId = parseRawOrEmpty(call.request.queryParameters, "messageId")
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
            if (!expectedHash.matches(sha256HexRegex)) {
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
            val tempFile = BlobStore.createTempFile(attachmentId)
            val received = try {
                call.receiveEncryptedAttachment(tempFile, MAX_ATTACHMENT_CIPHER_BYTES)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                BlobStore.delete(attachmentId)
                throw cancelled
            } catch (error: Throwable) {
                BlobStore.delete(attachmentId)
                call.application.log.warn("Encrypted attachment receive failed", error)
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("附件上传中断"))
                return@post
            }
            if (received == null || received.byteCount != declaredLength || received.sha256 != expectedHash) {
                BlobStore.delete(attachmentId)
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("附件长度或哈希校验失败"))
                return@post
            }
            val expiresAt = System.currentTimeMillis() + ATTACHMENT_UPLOAD_TTL_MS
            val stored = runCatching {
                BlobStore.finalizeUpload(attachmentId, tempFile)
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
                BlobStore.delete(attachmentId)
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
            stored.getOrThrow().forEach(BlobStore::delete)
            call.respond(
                HttpStatusCode.Created,
                AttachmentUploadResponse(attachmentId, received.sha256, received.byteCount, expiresAt)
            )
        }
    }
}

private data class ReceivedEncryptedAttachment(val byteCount: Long, val sha256: String)
private suspend fun ApplicationCall.receiveEncryptedAttachment(
    target: java.io.File,
    maxBytes: Long
): ReceivedEncryptedAttachment? {
    val digest = sha256ThreadLocal.get().apply { reset() }
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
