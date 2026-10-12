package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.AuthResponse
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.LoginRequest
import com.maodouchat.server.repository.AuthTokenRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.loginWithFactors
import com.maodouchat.server.service.LoginAttemptGate
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.slf4j.LoggerFactory

private val loginAuditLogger = LoggerFactory.getLogger("LoginAudit")

// 登录：账号/密码 + TOTP 会话签发。
internal fun Route.configureAuthLoginLoginRoutes(
    userRepo: UserRepository,
    authTokenRepo: AuthTokenRepository,
    loginIpRateLimiter: BoundedRateLimiter,
    loginEmailRateLimiter: BoundedRateLimiter,
    loginGate: LoginAttemptGate,
) {
        post("/api/auth/login") {
            // 登录 IP 频率限制 — 防暴破（读取 AUTH_RATE_LIMIT_PER_MINUTE）
            if (!loginIpRateLimiter.acquire(call.remoteHost(), maxPerMinute = ServerConfig.authRateLimitPerMinute)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("登录过于频繁，请稍后再试"))
                return@post
            }
            val req = call.receiveJson<LoginRequest>()
            if (req == null || req.email.isBlank() || req.password.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("邮箱或密码不能为空"))
                return@post
            }
            // 按账号限流：即使攻击者轮换源 IP，对同一邮箱的尝试仍受限于单机速率
            val emailKey = runCatching { req.email.normalizedEmail() }.getOrDefault(req.email)
            if (!loginEmailRateLimiter.acquire(emailKey, maxPerMinute = ServerConfig.authRateLimitPerMinute)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("该账号尝试过于频繁，请稍后再试"))
                return@post
            }
            // 单账号失败锁定检查：锁定期间直接拒绝，不泄露密码正误（与失败提示一致）
            // 8.51 修复 M1：锁定按「账号|源 IP」隔离，远程失败不影响受害者自身 IP 登录
            val ip = call.remoteHost()
            loginGate.sweep()
            val accountLockKey = loginGate.key(emailKey, ip)
            if (loginGate.isLocked(accountLockKey)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("该账号已被临时锁定，请稍后再试", code = "ACCOUNT_LOCKED"))
                return@post
            }
            // 8.40：锁定期满即清除失败计数——此前计数只随成功登录清空，期满后任意一次
            // 失败会立即重新锁定 15 分钟，攻击者只需周期性错 1 次即可无限期锁死账号（可用性 DoS）。
            // 仅清除「确实锁定过且已过期」的条目：lockUntil=0 表示从未锁定，不得移除，
            // 否则每次失败后计数被清空、锁定永远不会触发。
            loginGate.clearIfExpired(accountLockKey)
            val loginResult = userRepo.loginWithFactors(req.email, req.password, req.totpCode)
            val authed = loginResult.user != null
            // 9.5xx：登录全链路日志——管理后台「登不进」排障：每次尝试记录账号/来源/结果
            loginAuditLogger.info(
                // G328c：邮箱脱敏后再落 INFO——排障要的是「哪个账号」，不是明文邮箱。
                // 完整地址只在 DEBUG（默认不落盘）可用，见 maskEmail 的说明。
                "login attempt email={} ip={} user={} passwordOk={} totpEnabled={} totpOk={}",
                maskEmail(emailKey),
                ip,
                loginResult.user?.id.orEmpty(),
                loginResult.passwordOk,
                loginResult.totpEnabled,
                loginResult.totpOk
            )
            if (!authed) {
                // 密码错误 / TOTP 失败均计入连续失败，达阈值即锁定（按 IP 隔离）
                loginGate.recordFailure(emailKey, ip)
            }
            when {
                !loginResult.passwordOk -> {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid credentials", code = "AUTH_INVALID"))
                }
                loginResult.totpEnabled && !loginResult.totpOk -> {
                    // 200 so clients can parse requiresTotp without treating it as transport failure.
                    call.respond(
                        AuthResponse(requiresTotp = true, totpEnabled = true, userId = "", name = "", token = "")
                    )
                }
                authed -> {
                    // 登录成功：清除失败计数（按 IP 隔离），避免历史失败触发误锁
                    loginGate.clear(accountLockKey)
                    // G328c：这里原先每次登录成功都跑 `deleteExpired()`，而它按 refresh_tokens
                    // 逐行开事务（审计点名的 N+1）——过期积压时登录会变成事务风暴。
                    // `MaintenanceRunner` 已有 15 分钟一轮的 `authSessionExpiry` 在做同一件事，
                    // 登录路径上这次调用纯属重复，删掉。
                    call.respond(issueAuthResponse(checkNotNull(loginResult.user), authTokenRepo).copy(totpEnabled = loginResult.totpEnabled))
                }
                else -> {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid credentials", code = "AUTH_INVALID"))
                }
            }
        
        }
}
