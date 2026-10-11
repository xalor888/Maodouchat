package com.maodouchat.server.plugins

import io.ktor.server.routing.Route

// 评论（列表/发表/编辑/删除/评论点赞）（本轮按域拆为 2 簇，零行为改动）。
internal fun Route.configureSocialPostCommentRoutes(
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
    configureSocialPostCommentReadRoutes(postRepo = postRepo)

    configureSocialPostCommentWriteRoutes(userRepo = userRepo, postRepo = postRepo, moderationRuleRepo = moderationRuleRepo, aiGateway = aiGateway, pushService = pushService, commentRateLimiter = commentRateLimiter, commentLikeRateLimiter = commentLikeRateLimiter)
}
