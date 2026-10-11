package com.maodouchat.server.plugins

import com.maodouchat.server.auth.JwtConfig
import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.RefreshTokenRequest
import com.maodouchat.server.repository.AuthTokenRepository
import com.maodouchat.server.repository.PushTokenRepository
import com.maodouchat.server.service.SessionService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.request
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// 登出：吊销 refresh/access 会话并断开 WS。
internal fun Route.configureAuthLoginLogoutRoutes(
    authTokenRepo: AuthTokenRepository,
    pushTokenRepo: PushTokenRepository,
    loginIpRateLimiter: BoundedRateLimiter,
    sessionService: SessionService,
) {
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
