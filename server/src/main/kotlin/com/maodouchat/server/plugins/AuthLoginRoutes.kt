package com.maodouchat.server.plugins

import com.maodouchat.server.auth.JwtConfig
import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import com.maodouchat.server.service.LoginAttemptGate

private val loginAuditLogger = org.slf4j.LoggerFactory.getLogger("LoginAudit")
private val refreshAuditLogger = org.slf4j.LoggerFactory.getLogger("AuthRefreshAudit")

// 登录会话（登录/刷新/登出）。
internal fun Route.configureAuthLoginRoutes(
    userRepo: UserRepository,
    authTokenRepo: AuthTokenRepository,
    pushTokenRepo: PushTokenRepository,
    loginIpRateLimiter: BoundedRateLimiter,
    loginEmailRateLimiter: BoundedRateLimiter,
    loginGate: LoginAttemptGate,
    sessionService: com.maodouchat.server.service.SessionService,
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
        post("/api/auth/refresh") {
            // 9.3xx：此前共享 10/分/IP 的登录限流器——多设备/401 重试并发下正常轮换都被 429，
            // 客户端 refresh 失败即"假登录"（UI 卡在旧数据、无法恢复会话）。
            // refresh 本身有一次性轮换吊销兜底，这里放宽到 120/分/IP。
            if (!loginIpRateLimiter.acquire(call.remoteHost(), maxPerMinute = maxOf(ServerConfig.authRateLimitPerMinute, 120))) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                return@post
            }
            val req = call.receiveJsonOr400<RefreshTokenRequest>() ?: return@post
            // 单事务：校验封禁/账号存在后再 revoke，避免 peek→consume 窗口烧 refresh
            when (val rotated = authTokenRepo.rotateIfEligible(req.refreshToken.trim())) {
                is AuthTokenRepository.RotateRefreshResult.InvalidToken -> {
                    // 2026-09-27：为定位两设备 E2E 的「共享会话 401」抖动加的可观测性——
                    // 该模式怀疑为 refresh 轮换后的旧 token 重放（服务端反重放会撤销整个会话）。
                    // 成功路径不打日志（生产高频）；非成功路径各打一行，便于用服务端日志证伪/证实。
                    refreshAuditLogger.info("refresh rejected: invalid_token")
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("登录已过期，请重新登录"))
                    return@post
                }
                is AuthTokenRepository.RotateRefreshResult.UserSuspended -> {
                    // 8.42：与 InvalidToken 统一 401 通用文案——否则持有他人 refresh token 的
                    // 攻击者可探测账号封禁状态（账号状态 oracle）；封禁账号客户端走 401 正常登出
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("登录已过期，请重新登录"))
                    return@post
                }
                is AuthTokenRepository.RotateRefreshResult.UserMissing -> {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("登录已过期，请重新登录"))
                    return@post
                }
                is AuthTokenRepository.RotateRefreshResult.SessionCompromised -> {
                    // 反重放触发 = 有人拿**已轮换**的 refresh token 再刷新：整会话被撤销。
                    // 这是安全设计（不放松），但会导致该会话后续所有请求 401——
                    // 两设备 E2E 的「9 例共享会话 401」若为此因，这行日志就是证据。
                    refreshAuditLogger.warn(
                        "refresh token replay: auth session revoked (userId={} sessionId={})",
                        rotated.userId,
                        rotated.sessionId,
                    )
                    disconnectUserSessionsByAuthSessionIds(
                        rotated.userId,
                        setOf(rotated.sessionId),
                        "登录会话存在令牌重用，已撤销"
                    )
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("登录已过期，请重新登录"))
                    return@post
                }
                is AuthTokenRepository.RotateRefreshResult.Success -> {
                    val user = userRepo.getById(rotated.userId)
                    if (user == null) {
                        // 消费后账号被删的极端竞态：refresh 已吊销，只能要求重新登录
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("用户不存在"))
                        return@post
                    }
                    val response = issueAuthResponse(
                        user,
                        authTokenRepo,
                        IssuedRefreshToken(
                            token = rotated.refreshToken,
                            expiresAt = rotated.refreshExpiresAt,
                            sessionId = rotated.sessionId
                        )
                    )
                    call.respond(response)
                }
            }
        }
        post("/api/auth/logout") {
            // 9.3xx：登出必须永远可用——共享 10/分/IP 登录限流器导致"假登录"（会话 401 风暴后
            // logout 429，purge 流程中断，UI 永久卡在旧数据）
            if (!loginIpRateLimiter.acquire(call.remoteHost(), maxPerMinute = maxOf(ServerConfig.authRateLimitPerMinute, 120))) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                return@post
            }
            val req = call.receiveJsonOr400<RefreshTokenRequest>() ?: return@post
            // 优先从 refresh 行解析 userId（body-only logout 也能踢 WS）
            val revokedRefreshSession = authTokenRepo.revokeAndGetSession(req.refreshToken.trim())
            authTokenRepo.revokeAccessTokenFromAuthorizationHeader(call.request.headers[HttpHeaders.Authorization])
            val accessToken = call.request.headers[HttpHeaders.Authorization]
                ?.trim()
                ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                ?.substringAfter(' ')
                ?.trim()
                .orEmpty()
            val accessJwt = accessToken.takeIf { it.isNotBlank() }?.let {
                com.maodouchat.server.auth.JwtConfig.verifyToken(it)
            }
            val sessionsByUser = mutableMapOf<String, MutableSet<String>>()
            revokedRefreshSession?.sessionId?.let { sessionId ->
                sessionsByUser.getOrPut(revokedRefreshSession.userId) { mutableSetOf() }.add(sessionId)
            }
            val accessUserId = accessJwt?.subject?.takeIf { it.isNotBlank() }
            val accessSessionId = accessJwt?.let(JwtConfig::authSessionId)
            if (accessUserId != null && accessSessionId != null) {
                sessionsByUser.getOrPut(accessUserId) { mutableSetOf() }.add(accessSessionId)
            }
            sessionsByUser.forEach { (userId, sessionIds) ->
                sessionIds.forEach { sessionId ->
                    sessionService.endSession(userId, sessionId)
                }
                disconnectUserSessionsByAuthSessionIds(userId, sessionIds, "已退出登录")
            }
            val logoutUserId = revokedRefreshSession?.userId ?: accessUserId
            if (!logoutUserId.isNullOrBlank()) {
                if (sessionsByUser[logoutUserId].isNullOrEmpty()) {
                    disconnectUserSessionsByAccessJti(logoutUserId, accessJwt?.id, "已退出登录")
                }
                // 可选 deviceId：清除本机 FCM，避免已退出设备仍收推送（Android 也会先 unregister，此处兜底）
                val pushDeviceId = req.deviceId.trim()
                if (pushDeviceId.isNotBlank() && pushDeviceId.length <= 128) {
                    pushTokenRepo.remove(logoutUserId, pushDeviceId)
                }
            }
            call.respond(
                buildJsonObject {
put("status", "ok")
                }
            )
        }

}
