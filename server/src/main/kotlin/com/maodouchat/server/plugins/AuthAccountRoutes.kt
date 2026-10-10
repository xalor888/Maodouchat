package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*


// 账号注册与找回（注册/验证码发送/验证码注册/密码重置）。
internal fun Route.configureAuthAccountRoutes(
    userRepo: UserRepository,
    authTokenRepo: AuthTokenRepository,
    loginIpRateLimiter: BoundedRateLimiter,
    loginEmailRateLimiter: BoundedRateLimiter,
    sendCodeRateLimiter: BoundedRateLimiter,
    sendCodeIpRateLimiter: BoundedRateLimiter,
    sessionService: com.maodouchat.server.service.SessionService,
) {
 post("/api/auth/register") {
            if (ServerConfig.isProduction) {
                call.respond(HttpStatusCode.Gone, ErrorResponse("请使用验证码注册"))
                return@post
            }
            if (!RuntimeConfigService.isRegistrationAllowed()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("注册已关闭"))
                return@post
            }
            // 注册 IP 频率限制 — 防暴破（读取 AUTH_RATE_LIMIT_PER_MINUTE）
            if (!loginIpRateLimiter.acquire(call.remoteHost(), maxPerMinute = ServerConfig.authRateLimitPerMinute)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("注册过于频繁，请稍后再试"))
                return@post
            }
            val req = call.receiveJson<RegisterRequest>()
            if (req == null || req.name.isBlank() || req.email.isBlank() || !isValidPassword(req.password)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                return@post
            }
            if (userRepo.getByEmail(req.email) != null) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse("邮箱已注册"))
                return@post
            }
            // 0.74：一次性/垃圾邮箱域名黑名单（反垃圾注册）
            if (isRegistrationEmailDomainBlocked(req.email)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("该邮箱域名已被禁止注册", code = "EMAIL_DOMAIN_BLOCKED"))
                return@post
            }
            val user = try {
             // 用 try-catch 捕获并发注册时的唯一约束冲突（第二个请求在 byEmail 检查后才插入）
                userRepo.register(req.name, req.email, req.password)
            } catch (e: Exception) {
                // 8.34 修复：仅唯一约束冲突映射 409；其余异常（DB 故障等）此前被伪装成
                //「邮箱已注册」，掩盖真实失败、运维排障困难 → 交给 StatusPages 500 分支
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
        // 发送验证码 — 在 Dispatchers.IO 中同步阻塞等待邮件发送完成，避免阻塞 Netty 事件线程
            // purpose=register（默认）| reset；重置密码不依赖 allowRegistration
            post("/api/auth/send-code") {
                val req = call.receiveJsonOr400<SendCodeRequest>() ?: return@post
                val purpose = req.purpose.trim().lowercase().ifBlank { "register" }
                val isReset = purpose == com.maodouchat.server.service.EmailService.PURPOSE_RESET
                if (!isReset && !RuntimeConfigService.isRegistrationAllowed()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("注册已关闭"))
                    return@post
                }
                val email = runCatching { req.email.normalizedEmail() }.getOrNull()
                if (email == null || email.isBlank() || !email.contains("@")) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("邮件格式无效"))
                    return@post
                }
                // Reject blocked registration domains before consuming limiter capacity or
                // sending mail. Password-reset requests remain intentionally unaffected.
                if (!isReset && isRegistrationEmailDomainBlocked(email)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse("该邮箱域名已被禁止注册", code = "EMAIL_DOMAIN_BLOCKED")
                    )
                    return@post
                }
                // 频率限制：先检查 IP 级限制（防邮件轰炸），再检查邮箱级限制
                // 顺序很重要：如果先消费邮箱配额再被 IP 拒绝，共享 IP 下的用户会被误伤
                if (!sendCodeIpRateLimiter.acquireSendCodeIp(call.remoteHost())) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("该网络发送验证码次数过多，请稍后再试"))
                    return@post
                }
                // 每邮箱每分钟最多 3 次；拒绝请求不再继续扩充时间戳列表。
                if (!sendCodeRateLimiter.acquire(email, maxPerMinute = 3)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("发送过于频繁，请稍后再试"))
                    return@post
                }
                // 重置密码：账号不存在时仍返回 ok，避免邮箱枚举；内部跳过发信
                if (isReset && userRepo.getByEmail(email) == null) {
                    // 8.47 修复：等价延迟抗时间侧信道——已注册邮箱走 SMTP（数百 ms~数秒），
                    // 不存在邮箱即时返回可被攻击者测量时差枚举注册邮箱。统一延迟到可比量级。
                    withContext(Dispatchers.IO) { kotlinx.coroutines.delay(400L) }
                    call.respond(
                buildJsonObject {
put("status", "ok")
put("message", "验证码已发送")
                }
            )
                    return@post
                }
                try {
                    withContext(Dispatchers.IO) {
                        com.maodouchat.server.service.EmailService.sendVerificationCode(
                            email,
                            purpose = if (isReset) com.maodouchat.server.service.EmailService.PURPOSE_RESET
                            else com.maodouchat.server.service.EmailService.PURPOSE_REGISTER
                        )
                    }
                } catch (_: IllegalStateException) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("验证码邮件发送失败，请稍后重试"))
                    return@post
                }
                call.respond(
                buildJsonObject {
put("status", "ok")
put("message", "验证码已发送")
                }
            )
            }
        // 带验证码注册
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
        // 忘记密码：验证码 + 新密码；成功后吊销全部会话
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
