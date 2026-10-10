package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.ResetPasswordRequest
import com.maodouchat.server.repository.UserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 密码找回（验证码 + 新密码；成功后吊销全部会话）。 */
internal fun Route.configureAuthRecoveryRoutes(
    userRepo: UserRepository,
    loginIpRateLimiter: BoundedRateLimiter,
    loginEmailRateLimiter: BoundedRateLimiter,
    sessionService: com.maodouchat.server.service.SessionService,
) {
            post("/api/auth/reset-password") {
                if (!loginIpRateLimiter.acquire(call.remoteHost(), maxPerMinute = ServerConfig.authRateLimitPerMinute)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                    return@post
                }
                val req = call.receiveJsonOr400<ResetPasswordRequest>() ?: return@post
                val email = req.email.normalizedEmail()
                // 8.40：先校验再按账号限流（此前空邮箱先占限流额度再被 429 拒绝，且空值也扣配额）
                if (email.isBlank() || !email.contains("@") || req.code.isBlank() || !isValidPassword(req.newPassword)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效，新密码至少 6 位"))
                    return@post
                }
                // 按账号限流：关闭「多源 IP 分布式爆破重置同一账号」绕过
                if (!loginEmailRateLimiter.acquire(email, maxPerMinute = ServerConfig.authRateLimitPerMinute)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("该账号操作过于频繁，请稍后再试"))
                    return@post
                }
                if (!com.maodouchat.server.service.EmailService.verifyCode(
                        email,
                        req.code,
                        purpose = com.maodouchat.server.service.EmailService.PURPOSE_RESET
                    )
                ) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("验证码无效或已过期"))
                    return@post
                }
                val userId = userRepo.resetPasswordByEmail(email, req.newPassword)
                if (userId == null) {
                    // 验证码已消费；账号异常时与「码错误」区分开但避免枚举细节
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("无法重置密码，请确认邮箱后重试"))
                    return@post
                }
                sessionService.revokeAllUserSessions(userId)
                disconnectUserSessions(userId, "密码已重置，请重新登录")
                call.respond(
                    buildJsonObject {
    put("status", "ok")
    put("message", "密码已重置，请使用新密码登录")
                    }
                )
            }
}