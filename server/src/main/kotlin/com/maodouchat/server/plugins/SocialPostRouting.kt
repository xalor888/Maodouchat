package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.AiGateway
import com.maodouchat.server.service.ContentModerationService
import com.maodouchat.server.service.FcmPushService
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

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
    authenticate("auth-jwt") {
            post("/api/messages/{messageId}/star") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                if (!com.maodouchat.server.service.RuntimeConfigService.isMessageStarringEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("starring_disabled"))
                    return@post
                }
                val mid = call.parameters["messageId"]!!
                val starred = starMessageRepo.toggleStar(uid, mid)
                if (starred == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("该消息不能星标"))
                    return@post
                }
                call.respond(buildJsonObject { put("status", "ok"); put("starred", starred) })
            }

            // 会话消息置顶（群：管理员；单聊：双方）
            get("/api/chats/{chatId}/pins") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                val chatId = call.parameters["chatId"]!!
                if (!conversationParticipantRepo.isParticipant(chatId, uid)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作"))
                    return@get
                }
                call.respond(
                    PinnedMessagesListResponse(
                        chatId = chatId,
                        pins = pinnedMessageRepo.list(chatId)
                    )
                )
            }
            post("/api/chats/{chatId}/messages/{messageId}/pin") {
                if (call.rejectIfMaintenance()) return@post
                if (!RuntimeConfigService.isMessagePinEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("message_pin_disabled"))
                    return@post
                }
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                if (call.rejectIfSuspended(userRepo, uid)) return@post
                val chatId = call.parameters["chatId"]!!
                val mid = call.parameters["messageId"]!!
                if (!conversationParticipantRepo.isParticipant(chatId, uid)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作"))
                    return@post
                }
                val chat = conversationQueryRepo.getById(chatId)
                if (chat == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("聊天不存在"))
                    return@post
                }
                val actorIsManager = if (chat.isGroup) conversationParticipantRepo.isOwnerOrAdmin(chatId, uid) else true
                val outcome = pinnedMessageRepo.toggle(
                    chatId = chatId,
                    messageId = mid,
                    actorId = uid,
                    actorIsManager = actorIsManager
                )
                when (outcome.result) {
                    PinnedMessageRepository.PinResult.NOT_FOUND -> {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("消息不存在"))
                        return@post
                    }
                    PinnedMessageRepository.PinResult.FORBIDDEN -> {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("仅群主或管理员可置顶"))
                        return@post
                    }
                    PinnedMessageRepository.PinResult.LIMIT -> {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("最多置顶 ${PinnedMessageRepository.MAX_PINS_PER_CHAT} 条消息")
                        )
                        return@post
                    }
                    PinnedMessageRepository.PinResult.NOT_PINNABLE -> {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("该消息不能置顶"))
                        return@post
                    }
                    PinnedMessageRepository.PinResult.PINNED,
                    PinnedMessageRepository.PinResult.UNPINNED -> {
                        val pinned = outcome.result == PinnedMessageRepository.PinResult.PINNED
                        val payload = PinnedMessagesUpdatedPayload(chatId, uid, outcome.pins)
                        val pinJson = json.encodeToString(
                            WsMessage.serializer(),
                            WsMessage(
                                "PINNED_MESSAGES_UPDATED",
                                json.encodeToString(PinnedMessagesUpdatedPayload.serializer(), payload)
                            )
                        )
                        conversationParticipantRepo.participantIds(chatId).forEach { participantId ->
                            sendToUser(participantId, pinJson)
                        }
                        call.respond(
                            TogglePinResponse(
                                status = "ok",
                                pinned = pinned,
                                pins = outcome.pins
                            )
                        )
                    }
                }
            }
            get("/api/messages/starred") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                if (!com.maodouchat.server.service.RuntimeConfigService.isMessageStarringEnabled()) {
                    call.respond(emptyList<com.maodouchat.server.model.StarredMessageReference>())
                    return@get
                }
                val chatId = call.request.queryParameters["chatId"]
                call.respond(starMessageRepo.getStarredMessages(uid, chatId))
            }

            // 发现页 / 动态 API
            get("/api/posts") {

                if (!RuntimeConfigService.isPostsEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("posts_disabled"))
                    return@get
                }
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 30).coerceIn(1, 50)
                val before = call.request.queryParameters["before"]?.toLongOrNull()
                val beforeId = call.request.queryParameters["beforeId"]
                    ?.takeIf { before != null && it.isNotBlank() && it.length <= 100 }
                val authorId = call.request.queryParameters["authorId"]
                if (authorId != null) {
                    call.respond(postRepo.getPostsByAuthor(userId, authorId, limit, before, beforeId))
                } else {
                    call.respond(postRepo.getFeed(userId, limit, before, beforeId))
                }
            }

            post("/api/posts") {

                if (!RuntimeConfigService.isPostsEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("posts_disabled"))
                    return@post
                }
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (call.rejectIfPostRestricted(userRepo, userId)) return@post
                if (!postRateLimiter.acquire(userId, maxPerMinute = 20)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("发布过于频繁，请稍后再试"))
                    return@post
                }
                val req = call.receiveBoundedText()?.let { parseJson<CreatePostRequest>(it) } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("动态内容无效"))
                    return@post
                }
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
                val moderation = ContentModerationService.combine(
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
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (call.rejectIfPostRestricted(userRepo, userId)) return@post
                if (!postImageRateLimiter.acquire(userId, maxPerMinute = 10)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("图片上传过于频繁，请稍后再试"))
                    return@post
                }
                val req = call.receiveBoundedText(MAX_UPLOAD_JSON_BODY_CHARS)?.let { parseJson<UploadPostImageRequest>(it) }
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
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val filename = call.parameters["filename"].orEmpty()
                if (!com.maodouchat.server.service.FileStorageService.isOwnedPostImageFilename(filename, userId)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("动态图片无效"))
                    return@delete
                }
                postRepo.deleteUnclaimedPostImage(filename, userId)
                call.respond(HttpStatusCode.NoContent)
            }

            get("/api/posts/{id}") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val postId = call.parameters["id"]!!
                val post = postRepo.getPostById(postId, userId)
                if (post == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("动态不存在"))
                else call.respond(post)
            }

            delete("/api/posts/{id}") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val postId = call.parameters["id"]!!
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
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val postId = call.parameters["id"]!!
                if (!postRepo.exists(postId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("动态不存在"))
                    return@put
                }
                if (!postRepo.isAuthor(postId, userId)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权编辑该动态"))
                    return@put
                }
                val req = call.receiveBoundedText()?.let { parseJson<EditPostRequest>(it) } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求体无效"))
                    return@put
                }
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
                val moderation = ContentModerationService.combine(
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

            post("/api/posts/{id}/like") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (call.rejectIfSuspended(userRepo, userId)) return@post
                val postId = call.parameters["id"]!!
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
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val postId = call.parameters["id"]!!
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

            get("/api/posts/{id}/comments") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val postId = call.parameters["id"]!!
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 100)
                val before = call.request.queryParameters["before"]?.toLongOrNull()
                val beforeId = call.request.queryParameters["beforeId"]
                    ?.takeIf { before != null && it.isNotBlank() && it.length <= 100 }
                val comments = postRepo.getComments(postId, userId, limit, before, beforeId)
                if (comments == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("动态不存在"))
                else call.respond(comments)
            }

            // 1.93：动态点赞者列表
            get("/api/posts/{id}/likers") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val postId = call.parameters["id"]!!
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 100)
                val likers = postRepo.listPostLikers(postId, userId, limit)
                if (likers == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("动态不存在"))
                else call.respond(PostLikersResponse(postId, likers))
            }

            post("/api/posts/{id}/comments") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (call.rejectIfPostRestricted(userRepo, userId)) return@post
                if (!commentRateLimiter.acquire(userId, maxPerMinute = 30)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("评论过于频繁，请稍后再试"))
                    return@post
                }
                val postId = call.parameters["id"]!!
                val req = call.receiveBoundedText()?.let { parseJson<CreateCommentRequest>(it) }
                if (req == null) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效")); return@post }
                if (!isValidCommentPayload(req.content)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("评论内容无效"))
                    return@post
                }
                val commentPlain = req.content.trim()
                val keywordModeration = moderationRuleRepo.evaluate(userId, "COMMENT", commentPlain)
                val moderation = ContentModerationService.combine(
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
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (call.rejectIfPostRestricted(userRepo, userId)) return@put
                if (!commentRateLimiter.acquire(userId, maxPerMinute = 30)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("评论操作过于频繁，请稍后再试"))
                    return@put
                }
                val postId = call.parameters["id"]!!
                val cid = call.parameters["cid"]!!
                val req = call.receiveBoundedText()?.let { parseJson<UpdateCommentRequest>(it) }
                if (req == null || !isValidCommentPayload(req.content)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("评论内容无效"))
                    return@put
                }
                val commentPlain = req.content.trim()
                val keywordModeration = moderationRuleRepo.evaluate(userId, "COMMENT", commentPlain)
                val moderation = ContentModerationService.combine(
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
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val postId = call.parameters["id"]!!
                val cid = call.parameters["cid"]!!
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
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (call.rejectIfPostRestricted(userRepo, userId)) return@post
                val postId = call.parameters["id"]!!
                val cid = call.parameters["cid"]!!
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
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val postId = call.parameters["id"]!!
                val cid = call.parameters["cid"]!!
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

            // ─── 上传文件访问 API ────────────────
            // 必须经过 JWT 认证 — 旧 staticFiles("/uploads") 已被移除，避免 visibility 旁路
            // 头像：任何登录用户都可获取（头像本身是公开信息）
            get("/api/files/avatar/{filename}") {
                val filename = call.parameters["filename"]!!
                if (!filename.matches(Regex("^[A-Za-z0-9_.-]+$"))) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("文件名无效")); return@get }
                val avatarUrl = com.maodouchat.server.service.FileStorageService.avatarUrl(filename)
                if (avatarUrl == null || !userRepo.isCurrentAvatarUrl(avatarUrl)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("文件不存在"))
                    return@get
                }
                val file = com.maodouchat.server.service.FileStorageService.resolveFile("avatars", filename)
                if (file == null || !file.exists()) { call.respond(HttpStatusCode.NotFound, ErrorResponse("文件不存在")); return@get }
                call.respondFile(file)
            }
            get("/api/chats/{chatId}/avatar/file/{filename}") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val chatId = call.parameters["chatId"]!!
                val filename = call.parameters["filename"]!!
                if (!filename.matches(Regex("^[A-Za-z0-9_.-]+$"))) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("文件名无效")); return@get }
                val chat = conversationQueryRepo.getById(chatId)
                if (chat == null || !conversationParticipantRepo.isParticipant(chatId, userId)) { call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问群头像")); return@get }
                if (com.maodouchat.server.service.FileStorageService.groupAvatarFilename(chat.groupAvatar, chatId) != filename) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("群头像不存在")); return@get
                }
                val file = com.maodouchat.server.service.FileStorageService.resolveFile("group-avatars", filename)
                if (file == null || !file.exists()) { call.respond(HttpStatusCode.NotFound, ErrorResponse("文件不存在")); return@get }
                call.respondFile(file)
            }
            // 动态图片：通过 filename→postId 映射查找对应动态，再校验可见性
            get("/api/files/post-image/{filename}") {
                val filename = call.parameters["filename"]!!
                if (!filename.matches(Regex("^[A-Za-z0-9_.-]+$"))) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("文件名无效")); return@get }
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val postId = postRepo.findPostIdByImageFilename(filename)
                if (postId == null) { call.respond(HttpStatusCode.NotFound, ErrorResponse("文件不存在")); return@get }
                if (!postRepo.canView(postId, userId)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该动态图片"))
                    return@get
                }
                val file = com.maodouchat.server.service.FileStorageService.resolveFile("posts", filename)
                if (file == null || !file.exists()) { call.respond(HttpStatusCode.NotFound, ErrorResponse("文件不存在")); return@get }
                call.respondFile(file)
            }

        }
}
