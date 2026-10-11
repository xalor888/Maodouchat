package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 投票 legacy 查询簇：群投票列表 / 投票详情。 */
internal fun Route.configurePollLegacyQueryRoutes() {
    authenticate("auth-jwt") {
        get("/api/chats/{chatId}/polls") {
            val userId = call.requireUserId()
            val chatId = call.requirePathParamOr400("chatId", "missing chatId") ?: return@get
            // 8.32 一致性：非成员 403（此前仓库层静默过滤返回 200 []，与其余群资源 403 不一致）
            if (!com.maodouchat.server.repository.PollRepository.isMember(chatId, userId)) {
                return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该群"))
            }
            call.respond(com.maodouchat.server.repository.PollRepository.listChatPolls(chatId, userId))
        }

        get("/api/polls/{pollId}") {
            val userId = call.requireUserId()
            val pollId = call.requirePathParamOr400("pollId", "missing pollId") ?: return@get
            val poll = com.maodouchat.server.repository.PollRepository.getPoll(pollId, userId)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("poll not found"))
            call.respond(poll)
        }

    }
}
