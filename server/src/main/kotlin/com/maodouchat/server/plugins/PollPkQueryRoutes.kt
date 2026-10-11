package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.GroupCheckinRepository
import com.maodouchat.server.repository.PollRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.request
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 群 PK 查询簇：群 PK 列表 / PK 详情。 */
internal fun Route.configurePollPkQueryRoutes() {
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

        get("/api/pk/{pkId}") {
            val userId = call.requireUserId()
            val pkId = call.requirePathParamOr400("pkId", "missing pkId") ?: return@get
            val pk = GroupCheckinRepository.getPk(pkId, userId)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("PK 不存在"))
            call.respond(pk)
        }

    }
}
