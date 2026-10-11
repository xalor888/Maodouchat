package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.AiGateway
import com.maodouchat.server.service.ModerationEngine
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** 动态发布：发布/图片上传/未认领图片删除。 */
internal fun Route.configureSocialPostPublishRoutes(
    userRepo: UserRepository,
    postRepo: PostRepository,
    moderationRuleRepo: ModerationRuleRepository,
    aiGateway: AiGateway,
    postRateLimiter: BoundedRateLimiter,
    postImageRateLimiter: BoundedRateLimiter,
) {
    authenticate("auth-jwt") {
            post("/api/posts") {

                if (!RuntimeConfigService.isPostsEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("posts_disabled"))
                    return@post
                }
                val userId = call.requireUserId()
                if (call.rejectIfPostRestricted(userRepo, userId)) return@post
                if (!postRateLimiter.acquire(userId, maxPerMinute = 20)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("发布过于频繁，请稍后再试"))
                    return@post
                }
                val req = call.receiveJsonOr400<CreatePostRequest>(message = "动态内容无效") ?: return@post
                // Legacy clients always sent PUBLIC even when it only represented the UI default.
                // Preserve explicit CONTACTS/PRIVATE choices while failing closed for legacy PUBLIC.
                val useAccountDefault = req.useDefaultVisibility
                    ?: (req.visibility == null || req.visibility == "PUBLIC")
                val visibility = if (useAccountDefault) {
                    userRepo.getPrivacy(userId)?.defaultPostVisibility ?: "PRIVATE"
                } else {
                    req.visibility ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("动态可见性无效"))
                        return@post
                    }
                }
                if (!isValidPostPayload(req.content, req.imageUrls, visibility)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("动态内容无效"))
                    return@post
                }
                if (req.imageUrls.any { url ->
                        !com.maodouchat.server.service.FileStorageService.isOwnedPostImageUrl(url, userId)
                    }
                ) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("动态图片无效或不属于当前账号"))
                    return@post
                }
                val postPlain = buildString {
                    append(req.content.trim())
                    if (req.imageUrls.isNotEmpty()) append('\n').append(req.imageUrls.joinToString("\n"))
                }
                val keywordModeration = moderationRuleRepo.evaluate(
                    userId = userId,
                    source = "POST",
                    content = postPlain
                )
                val moderation = ModerationEngine.combine(
                    userId = userId,
                    source = "POST",
                    content = postPlain,
                    keyword = keywordModeration,
                    gateway = aiGateway,
                    rules = moderationRuleRepo
                )
                if (moderation.blocked) {
                    val status = if (moderation.action == "AUTO_RATE_LIMIT") HttpStatusCode.TooManyRequests else HttpStatusCode.UnprocessableEntity
                    call.respond(status, ErrorResponse(moderation.message ?: "内容未通过安全检查"))
                    return@post
                }
                val created = try {
                    postRepo.createPost(userId, req.content.trim(), req.imageUrls, visibility)
                } catch (error: IllegalArgumentException) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse(error.message ?: "动态图片已被使用"))
                    return@post
                }
                moderation.matches.mapNotNull { it.eventId }.takeIf { it.isNotEmpty() }?.let { ids ->
                    moderationRuleRepo.attachReference(ids, created.id)
                }
                call.respond(HttpStatusCode.Created, created)
            }

            post("/api/posts/images") {

                if (!RuntimeConfigService.isPostsEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("posts_disabled"))
                    return@post
                }
                val userId = call.requireUserId()
                if (call.rejectIfPostRestricted(userRepo, userId)) return@post
                if (!postImageRateLimiter.acquire(userId, maxPerMinute = 10)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("图片上传过于频繁，请稍后再试"))
                    return@post
                }
                val req = call.receiveJson<UploadPostImageRequest>(MAX_UPLOAD_JSON_BODY_CHARS)
                if (req == null) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效")); return@post }
                val imageUrl = try {
                    com.maodouchat.server.service.FileStorageService.savePostImage(req.base64Data, userId)
                } catch (e: IllegalArgumentException) {
                    // 不把内部校验明细回传给客户端，仅服务端日志保留上下文
                    call.application.log.warn("Post image upload rejected for user {}: {}", userId, e.message)
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("图片数据无效"))
                    return@post
                }
                call.respond(UploadPostImageResponse("ok", imageUrl))
            }

            delete("/api/posts/images/{filename}") {
                val userId = call.requireUserId()
                val filename = parseRawOrEmpty(call.parameters, "filename")
                if (!com.maodouchat.server.service.FileStorageService.isOwnedPostImageFilename(filename, userId)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("动态图片无效"))
                    return@delete
                }
                postRepo.deleteUnclaimedPostImage(filename, userId)
                call.respond(HttpStatusCode.NoContent)
            }
    }
}
