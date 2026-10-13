package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.clearUsername
import com.maodouchat.server.repository.setUsername
import com.maodouchat.server.service.CacheService
import com.maodouchat.server.service.invalidateUserProfile
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// 用户名：设置/清除（从 AccountProfileWriteRoutes 按域拆出）。
internal fun Route.configureAccountUsernameRoutes(
    userRepo: UserRepository,
    cacheService: CacheService,
    userSearchRateLimiter: BoundedRateLimiter,
) {
    authenticate("auth-jwt") {

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
