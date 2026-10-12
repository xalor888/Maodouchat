package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ServiceMessageRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.logCommand
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bot 删除自身消息（deleteMessage）。 */
internal fun Route.configureBotChatDeleteMessageRoutes(
    serviceMessageRepo: ServiceMessageRepository,
    userRepo: UserRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {

    post("/api/bot/deleteMessage") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val chatId: String
        val messageId: String
        when (val parsed = parseBotDeleteMessageFields(obj)) {
            is BotDeleteMessageFieldsResult.Ok -> {
                chatId = parsed.fields.chatId
                messageId = parsed.fields.messageId
            }
            BotDeleteMessageFieldsResult.Invalid ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/messageId required"))
        }
        val msg = serviceMessageRepo.getById(messageId)
        if (msg == null || msg.chatId != chatId) {
            return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("message not found"))
        }
        if (msg.senderId != bot.id) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("can only delete own bot messages"))
        }
        val deleted = runCatching {
            serviceMessageRepo.deleteOwn(messageId, bot.id)
        }.getOrNull()
        if (deleted != null) {
            fanoutBotEvent(
                userRepo = userRepo,
                participantRepository = conversationParticipantRepo,
                json = json,
                botId = bot.id,
                chatId = chatId,
                event = com.maodouchat.server.messaging.v2.ServiceMessagingV2Event(
                    action = "DELETE",
                    targetMessageId = messageId,
                ),
                messagingV2Repository = messagingV2Repository,
            )
        }
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, messageId, "deleteMessage")
        call.respond(
        buildJsonObject {
put("status", if (deleted != null) "ok" else "failed")
put("messageId", messageId)
        }
    )
    }
}
