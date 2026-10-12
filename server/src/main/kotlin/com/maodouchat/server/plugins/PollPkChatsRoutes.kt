package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.GroupCheckinRepository
import com.maodouchat.server.repository.PollRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** 群 PK 簇：群聊上下文的 PK 创建/列表（从 PollPkQueryRoutes、PollPkManageRoutes 按域拆出）。 */
internal fun Route.configurePollPkChatsRoutes() {
    authenticate("auth-jwt") {
        get("/api/chats/{chatId}/pk") {
            val userId = call.requireUserId()
            val chatId = call.requirePathParamOr400("chatId", "missing chatId") ?: return@get
            if (!PollRepository.isMember(chatId, userId)) {
                return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该群"))
            }
            val limit = parseGroupPlayLimit(call.request.queryParameters)
            call.respond(GroupCheckinRepository.listChatPks(chatId, userId, limit))
        }
        post("/api/chats/{chatId}/pk") {
            val userId = call.requireUserId()
            if (call.rejectIfSuspendedForPolls(userId)) return@post
            val chatId = call.requirePathParamOr400("chatId", "missing chatId") ?: return@post
            // 8.47：成员校验先于限流（同 checkin 口径）
            if (!PollRepository.isMember(chatId, userId)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该群"))
            }
            if (call.rejectIfMutedForPolls(chatId, userId)) return@post
            if (!pollRateLimiter.acquire("$userId:$chatId:pk_create", maxPerMinute = 10)) {
                return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("创建 PK 过于频繁"))
            }
            val obj = call.requireJsonObjectOr400(call.receiveBoundedTextOrEmpty(MAX_BODY_CHARS)) ?: return@post
            val fields = parsePkCreateFields(obj)
            if (fields.leftTitle.isBlank() || fields.rightTitle.isBlank() ||
                fields.leftTitle.length > MAX_PK_TITLE_LENGTH || fields.rightTitle.length > MAX_PK_TITLE_LENGTH
            ) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("PK 双方标题无效"))
            }
            // 8.32 一致性：非成员 403（与群管理端点一致），其余失败保持 400
            val pk = GroupCheckinRepository.createPk(chatId, userId, fields.leftTitle, fields.rightTitle)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("无法创建 PK"))
            broadcastGroupPlayUpdate(chatId, "pk_created") { viewerId ->
                GroupCheckinRepository.getPk(pk.id, viewerId)
            }
            call.respond(pk)
        }
    }
}
