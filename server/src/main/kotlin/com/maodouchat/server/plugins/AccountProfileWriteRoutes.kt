package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.CacheService
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.auth.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.server.application.application
import io.ktor.server.application.log
import kotlinx.serialization.json.*

/** 账号资料写操作：头像/用户名/资料修改。 */
internal fun Route.configureAccountProfileWriteRoutes(
    userRepo: UserRepository,
    cacheService: CacheService,
    avatarRateLimiter: BoundedRateLimiter,
    userSearchRateLimiter: BoundedRateLimiter,
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

        put("/api/users/profile") {
            val userId = call.optionalUserId()
            if (userId == null) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("未认证")); return@put }
            // 8.33 修复：封禁用户不得修改资料（此前仅部分写路径有检查）
            if (call.rejectIfSuspended(userRepo, userId)) return@put
            val req = call.receiveJson<UpdateProfileRequest>()
            if (req == null) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效")); return@put }
            val before = userRepo.getById(userId)
            userRepo.updateProfile(userId, name = req.name, status = req.status)
            val updated = userRepo.getById(userId)
            // 0.93：资料变更失效公开主页缓存
            before?.username?.let { cacheService.invalidateUserProfile("user_profile:$it") }
            if (updated != null) call.respond(updated)
            else call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
        }

        put("/api/users/me/username") {
            val userId = call.optionalUserId()
            if (userId == null) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("未认证")); return@put }
            // 8.38：封禁用户不得改用户名（与头像/资料/附近位置一致）；且设置带限流防占用枚举
            if (call.rejectIfSuspended(userRepo, userId)) return@put
            if (!userSearchRateLimiter.acquire("username:$userId", maxPerMinute = 10)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                return@put
            }
            val obj = call.receiveBoundedText()?.let(::parseJsonObjectEnvelopeOrNull)
                ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
            val username = parseAccountUsername(obj)
            // 8.40：格式非法 400、已占用 409 分离（此前一律 409，客户端无法区分参数错误与冲突）
            if (username.length !in 3..50 || !username.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
                return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("用户名格式无效（3-50 位字母/数字/_/-）"))
            }
            val result = userRepo.setUsername(userId, username)
            if (result != null) {
                // 0.93：用户名变更失效旧/新公开主页缓存
                cacheService.invalidateUserProfile("user_profile:$username")
                call.respond(
            buildJsonObject {
put("ok", true)
put("username", result)
            }
        )
            } else {
                call.respond(HttpStatusCode.Conflict, ErrorResponse("用户名不可用（已占用或格式无效）"))
            }
        }

        delete("/api/users/me/username") {
            val userId = call.optionalUserId()
            if (userId == null) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("未认证")); return@delete }
            if (call.rejectIfSuspended(userRepo, userId)) return@delete
            userRepo.clearUsername(userId)
            call.respond(
            buildJsonObject {
put("ok", true)
            }
        )
        }
    }
}
