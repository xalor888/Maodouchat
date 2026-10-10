package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.BlobStore
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** 附件分片上传会话：状态查询（GET /api/attachment-uploads/{id}）。 */
internal fun Route.configureAttachmentUploadSessionQueryRoutes(
    encryptedAttachmentRepo: EncryptedAttachmentRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
) {
    authenticate("auth-jwt") {



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
    }
}
