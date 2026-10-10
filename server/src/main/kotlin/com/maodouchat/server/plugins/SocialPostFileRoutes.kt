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

// 文件名白名单：原来三个 handler 每次请求都在内联里编译一次，提到文件级复用。
private val filenameAllowlistRegex = Regex("^[A-Za-z0-9_.-]+$")

// 文件读取（头像/群头像/动态图片；文件名白名单随迁）。
internal fun Route.configureSocialPostFileRoutes(
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
            // ─── 上传文件访问 API ────────────────
            // 必须经过 JWT 认证 — 旧 staticFiles("/uploads") 已被移除，避免 visibility 旁路
            // 头像：任何登录用户都可获取（头像本身是公开信息）

            get("/api/files/avatar/{filename}") {
                val filename = call.requirePathParamOr400("filename", "缺少文件名") ?: return@get
                if (!filename.matches(filenameAllowlistRegex)) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("文件名无效")); return@get }
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
                val userId = call.requireUserId()
                val chatId = call.requirePathParamOr400("chatId", "缺少聊天 ID") ?: return@get
                val filename = call.requirePathParamOr400("filename", "缺少文件名") ?: return@get
                if (!filename.matches(filenameAllowlistRegex)) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("文件名无效")); return@get }
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
                val filename = call.requirePathParamOr400("filename", "缺少文件名") ?: return@get
                if (!filename.matches(filenameAllowlistRegex)) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("文件名无效")); return@get }
                val userId = call.requireUserId()
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
