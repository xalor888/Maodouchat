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

/** Bot 地理·查询：投票列表/聊天历史。 */
internal fun Route.configureBotGeoQueryRoutes(
    conversationParticipantRepo: ConversationParticipantRepository,
    serviceMessageRepo: ServiceMessageRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
) {

        get("/api/bot/listChatPolls") {
            val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
            val chatId = call.requireNonBlankParamOr400("chatId", "chatId required") ?: return@get
            if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
                return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
            }
            val polls = com.maodouchat.server.repository.PollRepository.listChatPolls(chatId, bot.id)
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "listChatPolls")
            call.respond(
            buildJsonObject {
    put("chatId", chatId)
    putJsonElement("polls", polls)
    put("count", polls.size)
            }
        )
        }

        get("/api/bot/getChatHistory") {
            val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
            val chatId = call.requireNonBlankParamOr400("chatId", "chatId required") ?: return@get
            val limit = parseAdminListLimit(call.request.queryParameters, maxLimit = 100)
            if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
                return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
            }
            // Bot history exposes only server-authored service messages; peer E2EE bodies stay device-local.
            val history = serviceMessageRepo.list(chatId, limit, bot.id)
            // 8.46：混合类型 List<Map<String,Any>> encodeToString 运行时抛 SerializationException → buildJsonArray
            val historyArray = buildJsonArray {
                history.forEach { m ->
                    add(buildJsonObject {
                        put("messageId", m.id)
                        put("chatId", m.chatId)
                        put("senderId", m.senderId)
                        put("type", m.type)
                        put("timestamp", m.timestamp)
                        put("status", m.status)
                        put("content", m.content.take(4000))
                        put("sealedSender", m.sealedSender)
                    })
                }
            }
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "getChatHistory")
            call.respond(
            buildJsonObject {
    put("chatId", chatId)
    put("messages", historyArray)
    put("count", history.size)
            }
        )
        }
}
