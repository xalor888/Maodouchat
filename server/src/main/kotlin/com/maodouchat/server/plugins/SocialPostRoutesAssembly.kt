package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import com.maodouchat.server.service.AiGateway
import com.maodouchat.server.service.FcmPushService
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

// 动态五组路由的装配入口：五组签名完全一致，Routing.kt 只留一处调用。
internal fun Route.configureSocialPostRoutes(
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
    configureSocialPostStarPinRoutes(
        userRepo = userRepo,
        postRepo = postRepo,
        moderationRuleRepo = moderationRuleRepo,
        aiGateway = aiGateway,
        pushService = pushService,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        starMessageRepo = starMessageRepo,
        pinnedMessageRepo = pinnedMessageRepo,
        postRateLimiter = postRateLimiter,
        postImageRateLimiter = postImageRateLimiter,
        commentRateLimiter = commentRateLimiter,
        postLikeRateLimiter = postLikeRateLimiter,
        commentLikeRateLimiter = commentLikeRateLimiter,
        json = json,
    )
    configureSocialPostCoreRoutes(
        userRepo = userRepo,
        postRepo = postRepo,
        moderationRuleRepo = moderationRuleRepo,
        aiGateway = aiGateway,
        pushService = pushService,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        starMessageRepo = starMessageRepo,
        pinnedMessageRepo = pinnedMessageRepo,
        postRateLimiter = postRateLimiter,
        postImageRateLimiter = postImageRateLimiter,
        commentRateLimiter = commentRateLimiter,
        postLikeRateLimiter = postLikeRateLimiter,
        commentLikeRateLimiter = commentLikeRateLimiter,
        json = json,
    )
    configureSocialPostLikeRoutes(
        userRepo = userRepo,
        postRepo = postRepo,
        moderationRuleRepo = moderationRuleRepo,
        aiGateway = aiGateway,
        pushService = pushService,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        starMessageRepo = starMessageRepo,
        pinnedMessageRepo = pinnedMessageRepo,
        postRateLimiter = postRateLimiter,
        postImageRateLimiter = postImageRateLimiter,
        commentRateLimiter = commentRateLimiter,
        postLikeRateLimiter = postLikeRateLimiter,
        commentLikeRateLimiter = commentLikeRateLimiter,
        json = json,
    )
    configureSocialPostCommentRoutes(
        userRepo = userRepo,
        postRepo = postRepo,
        moderationRuleRepo = moderationRuleRepo,
        aiGateway = aiGateway,
        pushService = pushService,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        starMessageRepo = starMessageRepo,
        pinnedMessageRepo = pinnedMessageRepo,
        postRateLimiter = postRateLimiter,
        postImageRateLimiter = postImageRateLimiter,
        commentRateLimiter = commentRateLimiter,
        postLikeRateLimiter = postLikeRateLimiter,
        commentLikeRateLimiter = commentLikeRateLimiter,
        json = json,
    )
    configureSocialPostFileRoutes(
        userRepo = userRepo,
        postRepo = postRepo,
        moderationRuleRepo = moderationRuleRepo,
        aiGateway = aiGateway,
        pushService = pushService,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        starMessageRepo = starMessageRepo,
        pinnedMessageRepo = pinnedMessageRepo,
        postRateLimiter = postRateLimiter,
        postImageRateLimiter = postImageRateLimiter,
        commentRateLimiter = commentRateLimiter,
        postLikeRateLimiter = postLikeRateLimiter,
        commentLikeRateLimiter = commentLikeRateLimiter,
        json = json,
    )
}
