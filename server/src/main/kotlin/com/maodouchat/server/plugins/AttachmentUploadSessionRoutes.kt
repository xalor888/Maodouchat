package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import io.ktor.server.routing.Route

/** 附件分片上传会话管理：创建 / 状态查询 / 分块续传。 */
internal fun Route.configureEncryptedAttachmentUploadSessionRoutes(
    userRepo: UserRepository,
    encryptedAttachmentRepo: EncryptedAttachmentRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    rateLimiter: BoundedRateLimiter,
) {
    configureAttachmentUploadSessionCreateRoutes(userRepo = userRepo, encryptedAttachmentRepo = encryptedAttachmentRepo, conversationParticipantRepo = conversationParticipantRepo, conversationQueryRepo = conversationQueryRepo, rateLimiter = rateLimiter)
    configureAttachmentUploadSessionQueryRoutes(encryptedAttachmentRepo = encryptedAttachmentRepo, conversationParticipantRepo = conversationParticipantRepo)
    configureAttachmentUploadSessionChunkRoutes(userRepo = userRepo, encryptedAttachmentRepo = encryptedAttachmentRepo, conversationParticipantRepo = conversationParticipantRepo, conversationQueryRepo = conversationQueryRepo, rateLimiter = rateLimiter)
}
