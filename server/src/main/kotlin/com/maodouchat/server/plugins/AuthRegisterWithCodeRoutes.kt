package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.RegisterWithCodeRequest
import com.maodouchat.server.repository.AuthTokenRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.isUniqueViolation
import com.maodouchat.server.repository.register
import com.maodouchat.server.service.RuntimeConfigService
import com.maodouchat.server.service.verifyCode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/** 验证码注册。 */
internal fun Route.configureAuthRegisterWithCodeRoutes(
    userRepo: UserRepository,
    authTokenRepo: AuthTokenRepository,
    loginIpRateLimiter: BoundedRateLimiter,
    loginEmailRateLimiter: BoundedRateLimiter,
) {
    post("/api/auth/register-with-code") {
    if (!RuntimeConfigService.isRegistrationAllowed()) {
    call.respond(HttpStatusCode.Forbidden, ErrorResponse("注册已关闭"))
    return@post
    }
    // 注册 IP 频率限制 — 防暴破（读取 AUTH_RATE_LIMIT_PER_MINUTE）
                    if (!loginIpRateLimiter.acquire(call.remoteHost(), maxPerMinute = ServerConfig.authRateLimitPerMinute)) {
                        call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("注册过于频繁，请稍后再试"))
                        return@post
                    }
                    val req = call.receiveJsonOr400<RegisterWithCodeRequest>() ?: return@post
                if (req.name.isBlank() || req.email.isBlank() || !isValidPassword(req.password)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@post
                }
                // 按账号限流：关闭「多源 IP 分布式爆破/刷注册」绕过
                val regEmailKey = runCatching { req.email.normalizedEmail() }.getOrDefault(req.email)
                if (!loginEmailRateLimiter.acquire(regEmailKey, maxPerMinute = ServerConfig.authRateLimitPerMinute)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("该账号操作过于频繁，请稍后再试"))
                    return@post
                }
                if (!com.maodouchat.server.service.EmailService.verifyCode(req.email, req.code)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("验证码无效或已过期"))
                    return@post
                }
                if (userRepo.getByEmail(req.email) != null) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("邮箱已注册"))
                    return@post
                }
                if (isRegistrationEmailDomainBlocked(req.email)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("该邮箱域名已被禁止注册", code = "EMAIL_DOMAIN_BLOCKED"))
                    return@post
                }
                // 用 try-catch 捕获并发注册时的唯一约束冲突，与 /api/auth/register 保持一致
                val user = try {
                    userRepo.register(req.name, req.email, req.password)
                } catch (e: Exception) {
                    // 8.34 修复：仅唯一约束冲突映射 409，其余异常如实抛出（500），不再伪装成「邮箱已注册」
                    if (userRepo.isUniqueViolation(e)) {
                        call.respond(HttpStatusCode.Conflict, ErrorResponse("邮箱已注册"))
                        return@post
                    }
                    throw e
                }
                if (user != null) {
                    call.respond(issueAuthResponse(user, authTokenRepo))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("注册失败"))
                }
            }
}
