package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.UserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

// 用户搜索：用户列表/关键字搜索（从 AccountDiscoveryRoutes 按域拆出）。
internal fun Route.configureAccountUserSearchRoutes(
    userRepo: UserRepository,
    userSearchRateLimiter: BoundedRateLimiter,
) {
    authenticate("auth-jwt") {

        get("/api/users") {
            val userId = call.requireUserId()
            if (!userSearchRateLimiter.acquire(userId, maxPerMinute = 30)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                return@get
            }
            // 8.38：与 /api/users/search 一致截断 q 到 100（底层 LIKE 四列全表扫描）
            val q = parseAccountSearchQuery(call.request.queryParameters)
            val limit = parseAccountPageLimit(call.request.queryParameters, 30)
            val offset = parseAccountPageOffset(call.request.queryParameters)
            if (q.isBlank()) {
                call.respond(userRepo.getAll(limit, offset = offset, viewerId = userId))
            } else {
                if (q.length < 2) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("搜索关键字至少 2 个字符"))
                    return@get
                }
                call.respond(userRepo.searchUsers(q, excludeUserId = userId, limit = limit, viewerId = userId))
            }
        }

        get("/api/users/search") {
            // 8.33 修复：q 截断到 100 字符（底层 LIKE 全表扫描，超长关键字无意义且放大成本）
            val q = parseAccountSearchQuery(call.request.queryParameters)
            val limit = parseAccountPageLimit(call.request.queryParameters, 30)
            if (q.length < 2) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("搜索关键字至少 2 个字符"))
                return@get
            }
            val userId = call.requireUserId()
            if (!userSearchRateLimiter.acquire(userId, maxPerMinute = 30)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                return@get
            }
            call.respond(userRepo.searchUsers(q, excludeUserId = userId, limit = limit, viewerId = userId))
        }
    }
}
