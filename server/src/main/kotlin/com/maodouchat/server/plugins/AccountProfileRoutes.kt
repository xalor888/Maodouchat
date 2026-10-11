package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import com.maodouchat.server.service.CacheService
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/** 账号资料：me/他人资料/头像/用户名/改密/注销。 */
internal fun Route.configureAccountProfileRoutes(
    userRepo: UserRepository,
    postRepo: PostRepository,
    sessionService: com.maodouchat.server.service.SessionService,
    cacheService: CacheService,
    groupMediaReferenceRepo: GroupMediaReferenceRepository,
    encryptedAttachmentRepo: EncryptedAttachmentRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    userSearchRateLimiter: BoundedRateLimiter,
    avatarRateLimiter: BoundedRateLimiter,
    json: Json
) {
    configureAccountProfileReadRoutes(userRepo = userRepo)
    configureAccountProfileWriteRoutes(
        userRepo = userRepo,
        cacheService = cacheService,
        avatarRateLimiter = avatarRateLimiter,
        userSearchRateLimiter = userSearchRateLimiter,
    )
    configureAccountSecurityRoutes(
        userRepo = userRepo,
        postRepo = postRepo,
        sessionService = sessionService,
        groupMediaReferenceRepo = groupMediaReferenceRepo,
        encryptedAttachmentRepo = encryptedAttachmentRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        json = json,
    )
}
