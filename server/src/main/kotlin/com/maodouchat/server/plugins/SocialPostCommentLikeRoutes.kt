package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.FcmPushService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

// 评论点赞：点赞/取消点赞（从 SocialPostCommentWriteRoutes 按域拆出）。
internal fun Route.configureSocialPostCommentLikeRoutes(
    userRepo: UserRepository,
    postRepo: PostRepository,
    pushService: FcmPushService,
    commentLikeRateLimiter: BoundedRateLimiter,
) {
    authenticate("auth-jwt") {

        // 1.52：评论点赞/取消点赞（1.83：独立限流与动态点赞隔离）
        post("/api/posts/{id}/comments/{cid}/like") {
            val userId = call.requireUserId()
            if (call.rejectIfPostRestricted(userRepo, userId)) return@post
            val postId = call.requirePathParamOr400("id", "缺少动态 ID") ?: return@post
            val cid = call.requirePathParamOr400("cid", "缺少评论 ID") ?: return@post
            if (!commentLikeRateLimiter.acquire(userId, maxPerMinute = 30)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                return@post
            }
            val (likeCount, newLike) = postRepo.likeComment(postId, cid, userId)
            if (likeCount < 0) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("评论不存在"))
                return@post
            }
            // 1.87：新点赞时通知评论作者（非本人、双向拉黑过滤）；1.113：互动类型细化 COMMENT_LIKE
            if (newLike) {
                val commentAuthor = postRepo.getCommentAuthorId(cid)
                if (commentAuthor != null && commentAuthor != userId && !userRepo.isBlockedEitherWay(commentAuthor, userId)) {
                    // 1.130：评论被赞附内容预览；1.132：附评论 id
                    val preview = postRepo.getComment(cid, userId)?.content
                    pushService.enqueuePostInteraction(commentAuthor, userId, postId, "COMMENT_LIKE", preview, cid)
                }
            }
            call.respond(
            buildJsonObject {
    put("status", "liked")
    put("likeCount", likeCount)
            }
        )
        }

        delete("/api/posts/{id}/comments/{cid}/like") {
            val userId = call.requireUserId()
            val postId = call.requirePathParamOr400("id", "缺少动态 ID") ?: return@delete
            val cid = call.requirePathParamOr400("cid", "缺少评论 ID") ?: return@delete
            if (!commentLikeRateLimiter.acquire(userId, maxPerMinute = 30)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                return@delete
            }
            val likeCount = postRepo.unlikeComment(postId, cid, userId)
            call.respond(
            buildJsonObject {
    put("status", "unliked")
    put("likeCount", likeCount)
            }
        )
        }
    }
}
