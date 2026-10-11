package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import com.maodouchat.server.service.AiGateway
import com.maodouchat.server.service.FcmPushService
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

// 动态发布与管理（列表/发布/图片上传/详情/编辑/删除）。
internal fun Route.configureSocialPostCoreRoutes(
    userRepo: UserRepository,
    postRepo: PostRepository,
    moderationRuleRepo: ModerationRuleRepository,
    aiGateway: AiGateway,
    pushService: FcmPushService,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    starMessageRepo: StarMessageRepository,
    pinnedMessageRepo: PinnedMessageRepository,
    postRateLimiter: BoundedRateLimiter,
    postImageRateLimiter: BoundedRateLimiter,
    commentRateLimiter: BoundedRateLimiter,
    postLikeRateLimiter: BoundedRateLimiter,
    commentLikeRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    configureSocialPostReadRoutes(
        userRepo = userRepo,
        postRepo = postRepo,
    )
    configureSocialPostPublishRoutes(
        userRepo = userRepo,
        postRepo = postRepo,
        moderationRuleRepo = moderationRuleRepo,
        aiGateway = aiGateway,
        postRateLimiter = postRateLimiter,
        postImageRateLimiter = postImageRateLimiter,
    )
    configureSocialPostManageRoutes(
        userRepo = userRepo,
        postRepo = postRepo,
        moderationRuleRepo = moderationRuleRepo,
        aiGateway = aiGateway,
    )
}
