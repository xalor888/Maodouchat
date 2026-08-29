package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 简介与成员计数（setMyDescription / getChatMemberCount）。 */
internal fun Route.configureBotProfileRoutes(
    conversationParticipantRepo: ConversationParticipantRepository,
    botSendRateLimiter: BoundedRateLimiter,
) {

    post("/api/bot/setMyDescription") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val description = (obj["description"] ?: obj["about"])?.jsonPrimitive?.content
        val updated = com.maodouchat.server.repository.BotRepository.setMyDescription(bot.id, description)
            ?: return@post call.respondBotUnavailable()
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "setMyDescription")
        call.respond(
        buildJsonObject {
put("ok", true)
put("botId", updated.id)
put("description", (updated.description ?: ""))
        }
    )
    }

    get("/api/bot/getChatMemberCount") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val chatId = call.request.queryParameters["chatId"].orEmpty()
        if (chatId.isBlank()) return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val count = conversationParticipantRepo.participantIds(chatId).size
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "getChatMemberCount")
        call.respond(
        buildJsonObject {
put("chatId", chatId)
put("count", count)
        }
    )
    }
}
