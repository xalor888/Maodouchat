package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import com.maodouchat.server.service.CacheService
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** 账号路由门面：按域拆为资料 / 推送 / 发现 / 隐私通知 4 簇（零行为改动）。 */
internal fun Route.configureAccountRoutes(
    userRepo: UserRepository,
    postRepo: PostRepository,
    authTokenRepo: AuthTokenRepository,
    pushTokenRepo: PushTokenRepository,
    sessionService: com.maodouchat.server.service.SessionService,
    notificationPreferenceRepo: NotificationPreferenceRepository,
    nearbyRepo: NearbyRepository,
    cacheService: CacheService,
    groupMediaReferenceRepo: GroupMediaReferenceRepository,
    encryptedAttachmentRepo: EncryptedAttachmentRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    userSearchRateLimiter: BoundedRateLimiter,
    nearbyUpdateRateLimiter: BoundedRateLimiter,
    nearbyQueryRateLimiter: BoundedRateLimiter,
    avatarRateLimiter: BoundedRateLimiter,
    json: Json,
) {

    configureAccountProfileRoutes(
        userRepo = userRepo,
        postRepo = postRepo,
        sessionService = sessionService,
        cacheService = cacheService,
        groupMediaReferenceRepo = groupMediaReferenceRepo,
        encryptedAttachmentRepo = encryptedAttachmentRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        userSearchRateLimiter = userSearchRateLimiter,
        avatarRateLimiter = avatarRateLimiter,
        json = json,
    )

    configureAccountPushRoutes(
        pushTokenRepo = pushTokenRepo,
    )

    configureAccountDiscoveryRoutes(
        userRepo = userRepo,
        nearbyRepo = nearbyRepo,
        userSearchRateLimiter = userSearchRateLimiter,
        nearbyUpdateRateLimiter = nearbyUpdateRateLimiter,
        nearbyQueryRateLimiter = nearbyQueryRateLimiter,
    )

    configureAccountPrivacyRoutes(
        userRepo = userRepo,
        notificationPreferenceRepo = notificationPreferenceRepo,
        json = json,
    )
}
