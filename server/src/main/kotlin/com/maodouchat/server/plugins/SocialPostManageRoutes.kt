package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.AiGateway
import com.maodouchat.server.service.ModerationEngine
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** 动态管理：编辑/删除。 */
internal fun Route.configureSocialPostManageRoutes(
    userRepo: UserRepository,
    postRepo: PostRepository,
    moderationRuleRepo: ModerationRuleRepository,
    aiGateway: AiGateway,
) {
    authenticate("auth-jwt") {
            delete("/api/posts/{id}") {
                val userId = call.requireUserId()
                val postId = call.requirePathParamOr400("id", "缺少动态 ID") ?: return@delete
                if (!postRepo.exists(postId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("动态不存在"))
                    return@delete
                }
                if (!postRepo.isAuthor(postId, userId)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权删除该动态"))
                    return@delete
                }
                postRepo.deletePost(postId, userId)
                broadcastPostDeleted(postId, actorId = userId)
                call.respond(
                buildJsonObject {
put("status", "ok")
                }
            )
            }

            put("/api/posts/{id}") {
                val userId = call.requireUserId()
                val postId = call.requirePathParamOr400("id", "缺少动态 ID") ?: return@put
                if (!postRepo.exists(postId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("动态不存在"))
                    return@put
                }
                if (!postRepo.isAuthor(postId, userId)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权编辑该动态"))
                    return@put
                }
                val req = call.receiveJsonOr400<EditPostRequest>(message = "请求体无效") ?: return@put
                val newContent = req.content.trim()
                if (newContent.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("动态内容不能为空"))
                    return@put
                }
                if (newContent.length > MAX_POST_CONTENT_LENGTH) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("动态内容超出长度限制"))
                    return@put
                }
                if (req.visibility != null && !isValidPostVisibility(req.visibility)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("可见范围无效"))
                    return@put
                }
                // 8.38：编辑动态同样过内容审核（发动态/评论均过，编辑此前绕过——
                // 已发布内容可借编辑改成触发规则的内容）
                val keywordModeration = moderationRuleRepo.evaluate(
                    userId = userId,
                    source = "POST",
                    content = newContent
                )
                val moderation = ModerationEngine.combine(
                    userId = userId,
                    source = "POST",
                    content = newContent,
                    keyword = keywordModeration,
                    gateway = aiGateway,
                    rules = moderationRuleRepo
                )
                if (moderation.blocked) {
                    val status = if (moderation.action == "AUTO_RATE_LIMIT") HttpStatusCode.TooManyRequests else HttpStatusCode.UnprocessableEntity
                    call.respond(status, ErrorResponse(moderation.message ?: "内容未通过安全检查"))
                    return@put
                }
                val updated = postRepo.updatePost(postId, userId, newContent, req.visibility)
                if (updated == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("动态不存在或更新失败"))
                    return@put
                }
                moderation.matches.mapNotNull { it.eventId }.takeIf { it.isNotEmpty() }?.let { ids ->
                    moderationRuleRepo.attachReference(ids, updated.id)
                }
                call.respond(updated)
            }
    }
}
