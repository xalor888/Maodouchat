package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 聊天动作查询（getChatPins）。 */
internal fun Route.configureBotChatActionQueryRoutes(
    conversationParticipantRepo: ConversationParticipantRepository,
    pinnedMessageRepo: PinnedMessageRepository,
    botSendRateLimiter: BoundedRateLimiter,
) {

    get("/api/bot/getChatPins") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val chatId = call.requireNonBlankParamOr400("chatId", "chatId required") ?: return@get
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val pins = pinnedMessageRepo.list(chatId)
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "getChatPins")
        call.respond(
        buildJsonObject {
put("chatId", chatId)
putJsonElement("pins", pins)
put("count", pins.size)
        }
    )
    }
}
