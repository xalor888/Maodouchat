package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 群成员查询（getChatMember）。 */
internal fun Route.configureBotMemberQueryRoutes(
    conversationParticipantRepo: ConversationParticipantRepository,
    botSendRateLimiter: BoundedRateLimiter,
) {

    get("/api/bot/getChatMember") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val chatId = parseRawOrEmpty(call.request.queryParameters, "chatId")
        val userId = parseRawOrEmpty(call.request.queryParameters, "userId")
        if (chatId.isBlank() || userId.isBlank()) {
            return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/userId required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val members = conversationParticipantRepo.groupMembers(chatId)
        val m = members.firstOrNull { it.userId == userId }
        if (m == null) {
            // 1:1 or non-group: check participant list
            val isMember = conversationParticipantRepo.isParticipant(chatId, userId)
            if (!isMember) return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("member not found"))
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, userId, "getChatMember")
            call.respond(
        buildJsonObject {
put("userId", userId)
put("status", "member")
put("role", "MEMBER")
        }
    )
        } else {
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, userId, "getChatMember")
            call.respond(
        buildJsonObject {
put("userId", m.userId)
put("name", m.name)
put("role", m.role)
put("title", (m.title ?: ""))
put("mutedUntil", m.mutedUntil)
put("status", if (m.mutedUntil > System.currentTimeMillis()) "restricted" else "member")
        }
    )
        }
    }
}
