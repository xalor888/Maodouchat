package com.maodouchat.server.plugins

import com.maodouchat.server.config.AdminAccess
import com.maodouchat.server.model.*
import com.maodouchat.server.service.DispositionService
import com.maodouchat.server.service.UserDispositionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun Route.configureAdminUserRestrictionRoutes(
    sessionService: com.maodouchat.server.service.SessionService,
    userDispositionService: UserDispositionService,
) {
// 用户处置（封禁/禁动态/禁消息）
    put("/users/{id}/status") {
        if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val actorId = call.requireUserId()
        val id = call.requirePathParamOr400("id", "缺少用户 ID") ?: return@put
        if (id == actorId) return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("不能修改自己的管理状态"))
        if (AdminAccess.isAdmin(id)) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("不能修改其他超级管理员"))
        val req = call.receiveAdminJson<UpdateUserStatusRequest>()
            ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求无效"))
        val bannedUntil = req.bannedUntil ?: 0L
        when (val result = userDispositionService.suspend(actorId, id, bannedUntil, req.reasonCode, req.note)) {
            is UserDispositionService.Result.Invalid ->
                return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
            UserDispositionService.Result.NotFound ->
                return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
            is UserDispositionService.Result.Applied -> {
                // 生效中的封禁需立刻废掉已签发会话，避免仅靠写路径的 suspended 检查被绕过
                if (bannedUntil > System.currentTimeMillis()) {
                    sessionService.revokeAllUserSessions(id)
                    // 封禁后旧设备不得再收推送
                    disconnectUserSessions(id, "账号已被临时封禁")
                }
                call.respond(
                    buildJsonObject {
                        put("status", "ok")
                        put("reasonCode", result.reasonCode)
                        put("appealNoticeZh", DispositionService.APPEAL_NOTICE_ZH)
                    }
                )
            }
        }
    }

    put("/users/{id}/post-restriction") {
        if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val actorId = call.requireUserId()
        val id = call.requirePathParamOr400("id", "缺少用户 ID") ?: return@put
        if (id == actorId) return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("不能限制自己的发帖权限"))
        if (AdminAccess.isAdmin(id)) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("不能限制其他超级管理员"))
        val req = call.receiveAdminJson<UpdatePostRestrictionRequest>()
            ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求无效"))
        val postRestrictedUntil = req.postRestrictedUntil ?: 0L
        when (val result = userDispositionService.restrictPosts(actorId, id, postRestrictedUntil, req.reasonCode, req.note)) {
            is UserDispositionService.Result.Invalid ->
                return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
            UserDispositionService.Result.NotFound ->
                return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
            is UserDispositionService.Result.Applied -> call.respond(
                buildJsonObject {
                    put("status", "ok")
                    put("postRestrictedUntil", postRestrictedUntil)
                    put("reasonCode", result.reasonCode)
                    put("appealNoticeZh", DispositionService.APPEAL_NOTICE_ZH)
                }
            )
        }
    }

    put("/users/{id}/message-restriction") {
        if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val actorId = call.requireUserId()
        val id = call.requirePathParamOr400("id", "缺少用户 ID") ?: return@put
        if (id == actorId) return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("不能限制自己的发消息权限"))
        if (AdminAccess.isAdmin(id)) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("不能限制系统主管理员"))
        val req = call.receiveAdminJson<UpdateMessageRestrictionRequest>()
            ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求无效"))
        val messageRestrictedUntil = req.messageRestrictedUntil ?: 0L
        when (val result = userDispositionService.restrictMessages(actorId, id, messageRestrictedUntil, req.reasonCode, req.note)) {
            is UserDispositionService.Result.Invalid ->
                return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
            UserDispositionService.Result.NotFound ->
                return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
            is UserDispositionService.Result.Applied -> call.respond(
                buildJsonObject {
                    put("status", "ok")
                    put("reasonCode", result.reasonCode)
                    put("appealNoticeZh", DispositionService.APPEAL_NOTICE_ZH)
                    put("messageRestrictedUntil", messageRestrictedUntil)
                }
            )
        }
    }
}
