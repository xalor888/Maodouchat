package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 群资料查询（getChatAdministrators）。 */
internal fun Route.configureBotChatAdminQueryRoutes(
    conversationParticipantRepo: ConversationParticipantRepository,
    botSendRateLimiter: BoundedRateLimiter,
) {

    get("/api/bot/getChatAdministrators") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val chatId = call.requireNonBlankParamOr400("chatId", "chatId required") ?: return@get
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val admins = conversationParticipantRepo.groupMembers(chatId)
            .filter { it.role == "OWNER" || it.role == "ADMIN" }
        // 8.46：混合类型 List<Map<String,Any>> encodeToString 运行时抛 SerializationException → buildJsonArray
        val adminsArray = buildJsonArray {
            admins.forEach { m ->
                add(buildJsonObject {
                    put("userId", m.userId)
                    put("name", m.name)
                    put("role", m.role)
                    put("title", m.title ?: "")
                    put("isOnline", m.isOnline)
                })
            }
        }
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "getChatAdministrators")
        call.respond(
        buildJsonObject {
put("chatId", chatId)
put("administrators", adminsArray)
put("count", admins.size)
        }
    )
    }
}
