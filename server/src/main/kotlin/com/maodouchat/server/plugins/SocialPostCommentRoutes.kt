package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.AiGateway
import com.maodouchat.server.service.FcmPushService
import com.maodouchat.server.service.ModerationEngine
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

// 评论（列表/发表/编辑/删除/评论点赞）。
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
    authenticate("auth-jwt") {
            get("/api/posts/{id}/comments") {
                val userId = call.requireUserId()
                val postId = call.requirePathParamOr400("id", "缺少动态 ID") ?: return@get
                val limit = parseAdminListLimit(call.request.queryParameters, maxLimit = 100)
                val before = parseSocialFeedBefore(call.request.queryParameters)
                val beforeId = parseSocialFeedBeforeId(call.request.queryParameters, before)
                val comments = postRepo.getComments(postId, userId, limit, before, beforeId)
                if (comments == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("动态不存在"))
                else call.respond(comments)
            }

            post("/api/posts/{id}/comments") {
                val userId = call.requireUserId()
                if (call.rejectIfPostRestricted(userRepo, userId)) return@post
                if (!commentRateLimiter.acquire(userId, maxPerMinute = 30)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("评论过于频繁，请稍后再试"))
                    return@post
                }
                val postId = call.requirePathParamOr400("id", "缺少动态 ID") ?: return@post
                val req = call.receiveJson<CreateCommentRequest>()
                if (req == null) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效")); return@post }
                if (!isValidCommentPayload(req.content)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("评论内容无效"))
                    return@post
                }
                val commentPlain = req.content.trim()
                val keywordModeration = moderationRuleRepo.evaluate(userId, "COMMENT", commentPlain)
                val moderation = ModerationEngine.combine(
                    userId = userId,
                    source = "COMMENT",
                    content = commentPlain,
                    keyword = keywordModeration,
                    gateway = aiGateway,
                    rules = moderationRuleRepo
                )
                if (moderation.blocked) {
                    val status = if (moderation.action == "AUTO_RATE_LIMIT") HttpStatusCode.TooManyRequests else HttpStatusCode.UnprocessableEntity
                    call.respond(status, ErrorResponse(moderation.message ?: "评论未通过安全检查"))
                    return@post
                }
                val comment = postRepo.addComment(postId, userId, req.content.trim(), req.replyToId)
                if (comment == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("动态不存在或回复目标不存在"))
                else {
                    moderation.matches.mapNotNull { it.eventId }.takeIf { it.isNotEmpty() }?.let { ids ->
                        moderationRuleRepo.attachReference(ids, comment.id)
                    }
                    val postAuthorId = postRepo.getPostAuthorId(postId)
                    postAuthorId?.let { authorId ->
                        if (!userRepo.isBlockedEitherWay(authorId, userId)) {
                            // 1.130：评论附内容预览；1.132：附评论 id
                            pushService.enqueuePostInteraction(authorId, userId, postId, "COMMENT", comment.content, comment.id)
                        }
                    }
                    // 1.80：回复目标作者也通知（非发帖者本人时，避免重复）；1.122：互动类型细化 REPLY
                    val replyToId = req.replyToId
                    if (!replyToId.isNullOrBlank()) {
                        val replyAuthor = postRepo.getCommentAuthorId(replyToId)
                        if (replyAuthor != null && replyAuthor != postAuthorId && !userRepo.isBlockedEitherWay(replyAuthor, userId)) {
                            pushService.enqueuePostInteraction(replyAuthor, userId, postId, "REPLY", comment.content, comment.id)
                        }
                    }
                    call.respond(HttpStatusCode.Created, comment)
                }
            }

            put("/api/posts/{id}/comments/{cid}") {
                val userId = call.requireUserId()
                if (call.rejectIfPostRestricted(userRepo, userId)) return@put
                if (!commentRateLimiter.acquire(userId, maxPerMinute = 30)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("评论操作过于频繁，请稍后再试"))
                    return@put
                }
                val postId = call.requirePathParamOr400("id", "缺少动态 ID") ?: return@put
                val cid = call.requirePathParamOr400("cid", "缺少评论 ID") ?: return@put
                val req = call.receiveJson<UpdateCommentRequest>()
                if (req == null || !isValidCommentPayload(req.content)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("评论内容无效"))
                    return@put
                }
                val commentPlain = req.content.trim()
                val keywordModeration = moderationRuleRepo.evaluate(userId, "COMMENT", commentPlain)
                val moderation = ModerationEngine.combine(
                    userId = userId,
                    source = "COMMENT",
                    content = commentPlain,
                    keyword = keywordModeration,
                    gateway = aiGateway,
                    rules = moderationRuleRepo
                )
                if (moderation.blocked) {
                    val status = if (moderation.action == "AUTO_RATE_LIMIT") HttpStatusCode.TooManyRequests else HttpStatusCode.UnprocessableEntity
                    call.respond(status, ErrorResponse(moderation.message ?: "评论未通过安全检查"))
                    return@put
                }
                val comment = postRepo.updateCommentForUser(cid, postId, userId, req.content.trim())
                if (comment == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("评论不存在或无权编辑"))
                else {
                    moderation.matches.mapNotNull { it.eventId }.takeIf { it.isNotEmpty() }?.let { ids ->
                        moderationRuleRepo.attachReference(ids, comment.id)
                    }
                    call.respond(comment)
                }
            }

            delete("/api/posts/{id}/comments/{cid}") {
                val userId = call.requireUserId()
                val postId = call.requirePathParamOr400("id", "缺少动态 ID") ?: return@delete
                val cid = call.requirePathParamOr400("cid", "缺少评论 ID") ?: return@delete
                val ok = postRepo.deleteCommentForUser(postId, cid, userId)
                if (ok) call.respond(
                buildJsonObject {
put("status", "deleted")
                }
            )
                else call.respond(HttpStatusCode.NotFound, ErrorResponse("评论不存在或无权删除"))
            }

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
