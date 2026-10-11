package com.maodouchat.server.plugins

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import com.maodouchat.server.repository.ConversationParticipantRepository
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bot 可见会话列表（getChats）。 */
internal fun Route.configureBotInfoChatsRoutes(
    botSendRateLimiter: BoundedRateLimiter,
    conversationParticipantRepo: ConversationParticipantRepository,
) {

    get("/api/bot/chats") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val chats = conversationParticipantRepo.chatIdsForUser(bot.id)
        call.respond(
        buildJsonObject {
putJsonElement("chatIds", chats)
put("count", chats.size)
        }
    )
    }
}
