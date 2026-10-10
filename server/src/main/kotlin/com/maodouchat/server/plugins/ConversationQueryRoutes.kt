package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.ConversationQueryRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 会话只读查询：列表 + 详情。 */
internal fun Route.configureConversationQueryRoutes(
    queryRepository: ConversationQueryRepository,
) {
    authenticate("auth-jwt") {
        get("/api/chats") {
            val userId = call.requireUserId()
            call.respond(queryRepository.listForUser(userId))
        }

        get("/api/chats/{id}") {
            val userId = call.requireUserId()
            val chatId = call.requireNonBlankParamOr400("id", "聊天 ID 无效") ?: return@get
            val chat = queryRepository.getForParticipant(chatId, userId) ?: run {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("聊天不存在"))
                return@get
            }
            call.respond(chat)
        }
    }
}
