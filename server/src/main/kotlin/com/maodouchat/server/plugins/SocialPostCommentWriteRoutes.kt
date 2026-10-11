package com.maodouchat.server.plugins

import com.maodouchat.server.repository.ModerationRuleRepository
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.AiGateway
import com.maodouchat.server.service.FcmPushService
import io.ktor.server.routing.Route

// 评论写门面：保留原签名，按域委托给内容/点赞 2 簇（零行为改动）。
internal fun Route.configureSocialPostCommentWriteRoutes(
    userRepo: UserRepository,
    postRepo: PostRepository,
    moderationRuleRepo: ModerationRuleRepository,
    aiGateway: AiGateway,
    pushService: FcmPushService,
    commentRateLimiter: BoundedRateLimiter,
    commentLikeRateLimiter: BoundedRateLimiter,
) {
    configureSocialPostCommentContentRoutes(
        userRepo = userRepo,
        postRepo = postRepo,
        moderationRuleRepo = moderationRuleRepo,
        aiGateway = aiGateway,
        pushService = pushService,
        commentRateLimiter = commentRateLimiter,
    )
    configureSocialPostCommentLikeRoutes(
        userRepo = userRepo,
        postRepo = postRepo,
        pushService = pushService,
        commentLikeRateLimiter = commentLikeRateLimiter,
    )
}
