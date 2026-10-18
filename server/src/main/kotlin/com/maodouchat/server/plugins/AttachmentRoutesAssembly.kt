package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import io.ktor.server.routing.Route

// 附件上传会话与下载两组路由的装配入口。
internal fun Route.configureAttachmentRoutes(
    userRepo: UserRepository,
    encryptedAttachmentRepo: EncryptedAttachmentRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    rateLimiter: BoundedRateLimiter,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {
    configureEncryptedAttachmentUploadSessionRoutes(
        userRepo = userRepo,
        encryptedAttachmentRepo = encryptedAttachmentRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        rateLimiter = rateLimiter,
    )
    configureEncryptedAttachmentDownloadRoutes(
        userRepo = userRepo,
        encryptedAttachmentRepo = encryptedAttachmentRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        rateLimiter = rateLimiter,
        messagingV2Repository = messagingV2Repository,
    )
}
