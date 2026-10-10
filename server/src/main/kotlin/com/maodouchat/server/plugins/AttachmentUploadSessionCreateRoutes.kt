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

/** 附件分片上传会话：创建（POST /api/attachment-uploads）。 */
internal fun Route.configureAttachmentUploadSessionCreateRoutes(
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
    }
}
