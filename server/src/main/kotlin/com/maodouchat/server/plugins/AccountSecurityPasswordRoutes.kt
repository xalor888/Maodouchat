package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.auth.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*

/** 账号安全：改密。 */
internal fun Route.configureAccountSecurityPasswordRoutes(
    userRepo: UserRepository,
    sessionService: com.maodouchat.server.service.SessionService,
) {
    authenticate("auth-jwt") {

        post("/api/users/change-password") {
            val userId = call.requireUserId()
            val req = call.receiveJsonOr400<ChangePasswordRequest>() ?: return@post
            if (req.oldPassword.isBlank() || !isValidPassword(req.newPassword)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("新密码至少 6 位"))
                return@post
            }
            val ok = userRepo.changePassword(userId, req.oldPassword, req.newPassword)
            if (ok) {
                sessionService.revokeAllUserSessions(userId)
                // 与 logout-all 一致：旧设备会话已废，推送 token 必须清掉，否则仍收消息/来电推送
                disconnectUserSessions(userId, "密码已修改，请重新登录")
                call.respondOk()
            } else {
                // 403：勿用 401 — 客户端 executeWithRefresh 会把带 Authorization 的 401 当会话过期并清库
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("原密码错误", code = "WRONG_PASSWORD"))
            }
        }
    }
}
