package com.maodouchat.server.plugins

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** 登录后会话管理：全设备登出。 */
internal fun Route.configureAuthSessionLogoutRoutes(
    sessionService: com.maodouchat.server.service.SessionService,
) {
    authenticate("auth-jwt") {
        post("/api/auth/logout-all") {
            val userId = call.requireUserId()
            sessionService.revokeAllUserSessions(userId)
            // 全设备登出后必须清掉推送 token，否则已退出设备仍可能收到来电唤醒。
            disconnectUserSessions(userId, "已在其他设备退出全部会话")
            call.respond(
            buildJsonObject {
put("status", "ok")
            }
        )
        }
    }
}
