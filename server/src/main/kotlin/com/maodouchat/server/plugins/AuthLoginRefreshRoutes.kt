package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.RefreshTokenRequest
import com.maodouchat.server.repository.AuthTokenRepository
import com.maodouchat.server.repository.RotateRefreshResult
import com.maodouchat.server.repository.getById
import com.maodouchat.server.repository.rotateIfEligible
import com.maodouchat.server.repository.IssuedRefreshToken
import com.maodouchat.server.repository.UserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.slf4j.LoggerFactory

private val refreshAuditLogger = LoggerFactory.getLogger("AuthRefreshAudit")

// 刷新：refresh token 轮换签发新会话。
internal fun Route.configureAuthLoginRefreshRoutes(
    userRepo: UserRepository,
    authTokenRepo: AuthTokenRepository,
    loginIpRateLimiter: BoundedRateLimiter,
) {
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
                is RotateRefreshResult.InvalidToken -> {
                    // 2026-09-27：为定位两设备 E2E 的「共享会话 401」抖动加的可观测性——
                    // 该模式怀疑为 refresh 轮换后的旧 token 重放（服务端反重放会撤销整个会话）。
                    // 成功路径不打日志（生产高频）；非成功路径各打一行，便于用服务端日志证伪/证实。
                    refreshAuditLogger.info("refresh rejected: invalid_token")
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("登录已过期，请重新登录"))
                    return@post
                }
                is RotateRefreshResult.UserSuspended -> {
                    // 8.42：与 InvalidToken 统一 401 通用文案——否则持有他人 refresh token 的
                    // 攻击者可探测账号封禁状态（账号状态 oracle）；封禁账号客户端走 401 正常登出
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("登录已过期，请重新登录"))
                    return@post
                }
                is RotateRefreshResult.UserMissing -> {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("登录已过期，请重新登录"))
                    return@post
                }
                is RotateRefreshResult.SessionCompromised -> {
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
                is RotateRefreshResult.Success -> {
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
}
