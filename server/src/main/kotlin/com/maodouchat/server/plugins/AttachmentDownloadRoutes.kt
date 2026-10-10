package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import io.ktor.server.routing.Route

/** 附件直传 / 下载（含 Range）/ 删除。 */
internal fun Route.configureEncryptedAttachmentDownloadRoutes(
    userRepo: UserRepository,
    encryptedAttachmentRepo: EncryptedAttachmentRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    rateLimiter: BoundedRateLimiter,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {
    configureAttachmentDirectUploadRoutes(userRepo = userRepo, encryptedAttachmentRepo = encryptedAttachmentRepo, conversationParticipantRepo = conversationParticipantRepo, conversationQueryRepo = conversationQueryRepo, rateLimiter = rateLimiter)
    configureAttachmentDirectDownloadRoutes(userRepo = userRepo, encryptedAttachmentRepo = encryptedAttachmentRepo, conversationParticipantRepo = conversationParticipantRepo, rateLimiter = rateLimiter, messagingV2Repository = messagingV2Repository)
    configureAttachmentDirectDeleteRoutes(encryptedAttachmentRepo = encryptedAttachmentRepo)
}
