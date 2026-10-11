package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 杂项查询（getChatIds）。 */
internal fun Route.configureBotChatMiscQueryRoutes(
    conversationParticipantRepo: ConversationParticipantRepository,
    botSendRateLimiter: BoundedRateLimiter,
) {

    get("/api/bot/getChatIds") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val chats = conversationParticipantRepo.chatIdsForUser(bot.id)
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "getChatIds")
        call.respond(
        buildJsonObject {
put("ok", true)
putJsonElement("chatIds", chats)
put("count", chats.size)
        }
    )
    }
}
