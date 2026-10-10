package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.AiGateway
import com.maodouchat.server.service.FcmPushService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

// 动态点赞（点赞/取消/点赞人列表）。
internal fun Route.configureSocialPostLikeRoutes(
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
    authenticate("auth-jwt") {
            post("/api/posts/{id}/like") {
                val userId = call.requireUserId()
                if (call.rejectIfSuspended(userRepo, userId)) return@post
                val postId = call.requirePathParamOr400("id", "缺少动态 ID") ?: return@post
                // 8.38：点赞/取消点赞限流——此前无限流可对作者反复 like/unlike 刷 FCM 通知
                if (!postLikeRateLimiter.acquire(userId, maxPerMinute = 30)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                    return@post
                }
                // 1.137：禁止给自己的动态点赞
                if (postRepo.getPostAuthorId(postId) == userId) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("不能给自己的动态点赞"))
                    return@post
                }
                val wasAlreadyLiked = postRepo.hasLiked(postId, userId)
                if (!postRepo.likePost(postId, userId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("动态不存在"))
                    return@post
                }
                val post = postRepo.getPostById(postId, userId)
                postRepo.getPostAuthorId(postId)?.let { authorId ->
                    if (!wasAlreadyLiked && !userRepo.isBlockedEitherWay(authorId, userId)) {
                        pushService.enqueuePostInteraction(authorId, userId, postId, "LIKE")
                    }
                }
                if (post != null) call.respond(post) else call.respond(
                buildJsonObject {
put("status", "ok")
                }
            )
            }

            delete("/api/posts/{id}/like") {
                val userId = call.requireUserId()
                val postId = call.requirePathParamOr400("id", "缺少动态 ID") ?: return@delete
                if (!postLikeRateLimiter.acquire(userId, maxPerMinute = 30)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                    return@delete
                }
                if (!postRepo.unlikePost(postId, userId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("动态不存在"))
                    return@delete
                }
                val post = postRepo.getPostById(postId, userId)
                if (post != null) call.respond(post) else call.respond(
                buildJsonObject {
put("status", "ok")
                }
            )
            }

            // 1.93：动态点赞者列表

            get("/api/posts/{id}/likers") {
                val userId = call.requireUserId()
                val postId = call.requirePathParamOr400("id", "缺少动态 ID") ?: return@get
                val limit = parseAdminListLimit(call.request.queryParameters, maxLimit = 100)
                val likers = postRepo.listPostLikers(postId, userId, limit)
                if (likers == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("动态不存在"))
                else call.respond(PostLikersResponse(postId, likers))
            }
    }
}
