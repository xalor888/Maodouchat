package com.maodouchat.server.plugins

import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.ModerationRuleRepository
import com.maodouchat.server.repository.PinnedMessageRepository
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.StarMessageRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.AiGateway
import com.maodouchat.server.service.FcmPushService
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

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
