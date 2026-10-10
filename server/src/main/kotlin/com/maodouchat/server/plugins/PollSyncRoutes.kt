package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.PollRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

/** 群玩法 B3 路由：投票同步快照。 */
internal fun Route.configurePollSyncRoutes() {
    authenticate("auth-jwt") {

        // ── 投票同步（补充端点，不重复 Routing.kt 已有 CRUD）──
        get("/api/chats/{chatId}/polls/sync") {
            val userId = call.requireUserId()
            val chatId = call.requirePathParamOr400("chatId", "missing chatId") ?: return@get
            if (!PollRepository.isGroupChat(chatId) || !PollRepository.isMember(chatId, userId)) {
                return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该群"))
            }
            val limit = parseGroupPlayLimit(call.request.queryParameters)
            call.respond(PollRepository.listChatPollSnapshots(chatId, limit, viewerId = userId))
        }
    }
}
