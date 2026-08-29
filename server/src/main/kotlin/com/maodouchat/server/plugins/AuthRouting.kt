package com.maodouchat.server.plugins

import com.maodouchat.server.auth.JwtConfig
import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

internal data class LoginLockout(var fails: Int, var lockUntil: Long, var lastFailureAt: Long = 0L)

private val loginAuditLogger = org.slf4j.LoggerFactory.getLogger("LoginAudit")

internal fun Route.configureAuthRoutes(
    userRepo: UserRepository,
    authTokenRepo: AuthTokenRepository,
    pushTokenRepo: PushTokenRepository,
    loginIpRateLimiter: BoundedRateLimiter,
    loginEmailRateLimiter: BoundedRateLimiter,
    sendCodeRateLimiter: BoundedRateLimiter,
    sendCodeIpRateLimiter: BoundedRateLimiter,
    loginLockouts: ConcurrentHashMap<String, LoginLockout>,
    sweepLoginLockouts: () -> Unit,
    recordLoginFailure: (String, String) -> Unit,
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
            val req = call.receiveBoundedText()?.let { parseJson<RegisterRequest>(it) }
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

        post("/api/auth/login") {
            // 登录 IP 频率限制 — 防暴破（读取 AUTH_RATE_LIMIT_PER_MINUTE）
            if (!loginIpRateLimiter.acquire(call.remoteHost(), maxPerMinute = ServerConfig.authRateLimitPerMinute)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("登录过于频繁，请稍后再试"))
                return@post
            }
            val req = call.receiveBoundedText()?.let { parseJson<LoginRequest>(it) }
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
            sweepLoginLockouts()
            val accountLockKey = "$emailKey|$ip"
            val lock = loginLockouts[accountLockKey]
            if (lock != null && lock.lockUntil > System.currentTimeMillis()) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("该账号已被临时锁定，请稍后再试", code = "ACCOUNT_LOCKED"))
                return@post
            }
            // 8.40：锁定期满即清除失败计数——此前计数只随成功登录清空，期满后任意一次
            // 失败会立即重新锁定 15 分钟，攻击者只需周期性错 1 次即可无限期锁死账号（可用性 DoS）。
            // 仅清除「确实锁定过且已过期」的条目：lockUntil=0 表示从未锁定，不得移除，
            // 否则每次失败后计数被清空、锁定永远不会触发。
            if (lock != null && lock.lockUntil > 0L && lock.lockUntil <= System.currentTimeMillis()) {
                loginLockouts.remove(accountLockKey)
            }
            val loginResult = userRepo.loginWithFactors(req.email, req.password, req.totpCode)
            val authed = loginResult.user != null
            // 9.5xx：登录全链路日志——管理后台「登不进」排障：每次尝试记录账号/来源/结果
            loginAuditLogger.info(
                "login attempt email={} ip={} user={} passwordOk={} totpEnabled={} totpOk={}",
                emailKey,
                ip,
                loginResult.user?.id.orEmpty(),
                loginResult.passwordOk,
                loginResult.totpEnabled,
                loginResult.totpOk
            )
            if (!authed) {
                // 密码错误 / TOTP 失败均计入连续失败，达阈值即锁定（按 IP 隔离）
                recordLoginFailure(emailKey, ip)
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
                    loginLockouts.remove(accountLockKey)
                    authTokenRepo.deleteExpired()
                    call.respond(issueAuthResponse(checkNotNull(loginResult.user), authTokenRepo).copy(totpEnabled = loginResult.totpEnabled))
                }
                else -> {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid credentials", code = "AUTH_INVALID"))
                }
            }
        
        }

        // 发送验证码 — 在 Dispatchers.IO 中同步阻塞等待邮件发送完成，避免阻塞 Netty 事件线程
            // purpose=register（默认）| reset；重置密码不依赖 allowRegistration
            post("/api/auth/send-code") {
                val req = call.receiveBoundedText()?.let { parseJson<SendCodeRequest>(it) } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@post
                }
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
                val req = call.receiveBoundedText()?.let { parseJson<RegisterWithCodeRequest>(it) } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@post
                }
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
            val req = call.receiveBoundedText()?.let { parseJson<ResetPasswordRequest>(it) } ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                return@post
            }
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
            authTokenRepo.rotateAccessTokenVersion(userId)
            pushTokenRepo.removeAllForUser(userId)
            disconnectUserSessions(userId, "密码已重置，请重新登录")
            call.respond(
                buildJsonObject {
put("status", "ok")
put("message", "密码已重置，请使用新密码登录")
                }
            )
        }

        post("/api/auth/refresh") {
            // 9.3xx：此前共享 10/分/IP 的登录限流器——多设备/401 重试并发下正常轮换都被 429，
            // 客户端 refresh 失败即"假登录"（UI 卡在旧数据、无法恢复会话）。
            // refresh 本身有一次性轮换吊销兜底，这里放宽到 120/分/IP。
            if (!loginIpRateLimiter.acquire(call.remoteHost(), maxPerMinute = maxOf(ServerConfig.authRateLimitPerMinute, 120))) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                return@post
            }
            val req = call.receiveBoundedText()?.let { parseJson<RefreshTokenRequest>(it) } ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                return@post
            }
            // 单事务：校验封禁/账号存在后再 revoke，避免 peek→consume 窗口烧 refresh
            when (val rotated = authTokenRepo.rotateIfEligible(req.refreshToken.trim())) {
                is AuthTokenRepository.RotateRefreshResult.InvalidToken -> {
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
            val req = call.receiveBoundedText()?.let { parseJson<RefreshTokenRequest>(it) } ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                return@post
            }
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
                    authTokenRepo.revokeSession(userId, sessionId)
                    pushTokenRepo.removeForAuthSession(userId, sessionId)
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

internal fun Route.configureAuthenticatedSessionRoutes(
    userRepo: UserRepository,
    authTokenRepo: AuthTokenRepository,
    pushTokenRepo: PushTokenRepository,
    totpManageRateLimiter: BoundedRateLimiter,
) {
    authenticate("auth-jwt") {
            post("/api/auth/logout-all") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                authTokenRepo.rotateAccessTokenVersion(userId)
                // 全设备登出后必须清掉推送 token，否则已退出设备仍可能收到来电唤醒。
                pushTokenRepo.removeAllForUser(userId)
                disconnectUserSessions(userId, "已在其他设备退出全部会话")
                call.respond(
                buildJsonObject {
put("status", "ok")
                }
            )
            }

            get("/api/auth/totp/status") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                call.respond(TotpStatusResponse(enabled = userRepo.isTotpEnabled(userId)))
            }

            // 0.77：重新生成恢复码（验证当前 TOTP；旧码全部作废）
            post("/api/auth/totp/recover-codes") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (!totpManageRateLimiter.acquire(userId, maxPerMinute = 5)) {
                    return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                }
                val body = call.receiveBoundedTextOrEmpty()
                val code = runCatching {
                    Json.parseToJsonElement(body).jsonObject["code"]?.jsonPrimitive?.content
                }.getOrNull().orEmpty()
                val codes = userRepo.regenerateBackupCodes(userId, code)
                if (codes == null) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid totp code", code = "TOTP_INVALID"))
                }
                call.respond(TotpStatusResponse(enabled = true, backupCodes = codes))
            }

            post("/api/auth/totp/setup") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val setup = userRepo.beginTotpSetup(userId)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("user not found"))
                call.respond(
                    TotpSetupResponse(
                        secret = setup.first,
                        otpauthUrl = setup.second,
                        enabled = false
                    )
                )
            }

            post("/api/auth/totp/confirm") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                // 8.40：2FA 管理端点限流 + 失败锁定——6 位码 ±1 窗口可爆破，此前无限流
                if (!totpManageRateLimiter.acquire(userId, maxPerMinute = 5)) {
                    return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                }
                val body = call.receiveBoundedTextOrEmpty()
                val code = runCatching {
                    Json.parseToJsonElement(body).jsonObject["code"]?.jsonPrimitive?.content
                }.getOrNull().orEmpty()
                val backupCodes = userRepo.confirmTotpSetup(userId, code)
                if (backupCodes == null) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid totp code", code = "TOTP_INVALID"))
                }
                // 0.75：恢复码明文仅此一次返回（App 提示用户妥善保存）
                call.respond(TotpStatusResponse(enabled = true, backupCodes = backupCodes))
            }

            post("/api/auth/totp/disable") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                // 8.40：与 confirm 一致限流；disable 需验码，爆破同样应被抑制
                if (!totpManageRateLimiter.acquire(userId, maxPerMinute = 5)) {
                    return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                }
                val body = call.receiveBoundedTextOrEmpty()
                val code = runCatching {
                    Json.parseToJsonElement(body).jsonObject["code"]?.jsonPrimitive?.content
                }.getOrNull().orEmpty()
                if (!userRepo.disableTotp(userId, code)) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid totp code", code = "TOTP_INVALID"))
                }
                call.respond(TotpStatusResponse(enabled = false))
            }
    }
}
