package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.UploadAvatarRequest
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.replaceAvatar
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// 头像：上传/删除（从 AccountProfileWriteRoutes 按域拆出）。
internal fun Route.configureAccountAvatarRoutes(
    userRepo: UserRepository,
    avatarRateLimiter: BoundedRateLimiter,
) {
    authenticate("auth-jwt") {

        post("/api/users/avatar") {
            val userId = call.requireUserId()
            // 8.33 修复：封禁用户不得更换头像/资料（与 profile 修改一致）
            if (call.rejectIfSuspended(userRepo, userId)) return@post
            if (!avatarRateLimiter.acquire(userId, maxPerMinute = 10)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("头像操作过于频繁，请稍后再试"))
                return@post
            }
            val req = call.receiveJson<UploadAvatarRequest>(MAX_UPLOAD_JSON_BODY_CHARS)
            if (req == null) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效")); return@post }
            val avatarUrl = try {
                com.maodouchat.server.service.FileStorageService.saveAvatar(req.base64Data, userId)
            } catch (e: IllegalArgumentException) {
                // 不把内部校验明细回传给客户端，仅服务端日志保留上下文
                call.application.log.warn("Avatar upload rejected for user {}: {}", userId, e.message)
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("头像数据无效"))
                return@post
            }
            val replacement = try {
                userRepo.replaceAvatar(userId, avatarUrl)
            } catch (error: Throwable) {
                com.maodouchat.server.service.FileStorageService.deleteAvatarUrl(avatarUrl, userId)
                throw error
            }
            if (replacement == null) {
                com.maodouchat.server.service.FileStorageService.deleteAvatarUrl(avatarUrl, userId)
                call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
                return@post
            }
            if (replacement.previousUrl != replacement.currentUrl) {
                com.maodouchat.server.service.FileStorageService.deleteAvatarUrl(replacement.previousUrl, userId)
            }
            call.respond(
            buildJsonObject {
    put("status", "ok")
    put("avatarUrl", avatarUrl)
            }
        )
        }

        delete("/api/users/avatar") {
            val userId = call.requireUserId()
            val replacement = userRepo.replaceAvatar(userId, null)
            if (replacement == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
                return@delete
            }
            com.maodouchat.server.service.FileStorageService.deleteAvatarUrl(replacement.previousUrl, userId)
            call.respondOk()
        }
    }
}
