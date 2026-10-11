package com.maodouchat.server.plugins

import io.ktor.server.routing.Route

// 星标与置顶（消息星标、会话置顶、星标列表）（本轮按域拆为 2 簇，零行为改动）。
internal fun Route.configureSocialPostStarPinRoutes(
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
    configureSocialPostStarRoutes(starMessageRepo = starMessageRepo)

    configureSocialPostPinRoutes(userRepo = userRepo, conversationParticipantRepo = conversationParticipantRepo, conversationQueryRepo = conversationQueryRepo, pinnedMessageRepo = pinnedMessageRepo, json = json)
}
