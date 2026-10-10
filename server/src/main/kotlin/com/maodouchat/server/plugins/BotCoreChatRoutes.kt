package com.maodouchat.server.plugins

import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 核心·查询：getChat。 */
internal fun Route.configureBotCoreChatRoutes(
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
) {

        get("/api/bot/getChat") {
            val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
            val chatId = call.requireNonBlankParamOr400("chatId", "chatId required") ?: return@get
            val chat = conversationQueryRepo.getById(chatId)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("chat not found"))
            if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
                return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
            }
            val members = conversationParticipantRepo.participantIds(chatId)
            call.respond(
                buildJsonObject {
                    put("id", chat.id)
                    put("isGroup", chat.isGroup)
                    put("title", (chat.groupName ?: ""))
                    put("description", (chat.groupAnnouncement ?: ""))
                    put("announcement", (chat.groupAnnouncement ?: ""))
                    put("memberCount", members.size)
                    put("botIsMember", (bot.id in members))
                }
            )
        }
}
