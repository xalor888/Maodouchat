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

/** Bot 静默消息发送。 */
internal fun Route.configureBotMessagingSendSilentRoutes(
    userRepo: UserRepository,
    serviceMessageRepo: ServiceMessageRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {
post("/api/bot/sendMessageSilent") {
    // Convenience wrapper: force silent when runtime allows
    val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
    if (!com.maodouchat.server.service.RuntimeConfigService.isSilentSendEnabled()) {
        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("silent_send_disabled"))
    }
    val body = call.receiveBoundedTextOrEmpty()
    val obj = call.requireJsonObjectOr400(body) ?: return@post
    val fields = when (val parsed = parseBotSendMessageSilentFields(obj)) {
        is BotSendMessageSilentFieldsResult.Ok -> parsed.fields
        BotSendMessageSilentFieldsResult.MissingRequired ->
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/text required"))
    }
    val chatId = fields.chatId
    val text = fields.text
    val msgType = fields.msgType
    if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
    }
    val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
    val now = System.currentTimeMillis()
    val contentOut = text
    val ok = runCatching {
        serviceMessageRepo.insert(msgId, chatId, bot.id, contentOut, now, msgType)
    }.getOrDefault(false)
    if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
    com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendMessageSilent")
    val botMessage = com.maodouchat.server.model.MessageResponse(
        id = msgId, chatId = chatId, senderId = bot.id, content = contentOut,
        type = msgType, timestamp = now, status = "SENT"
    )
    // silent: still deliver WS, but clients should suppress push (server push path checks silent if present)
    fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage, messagingV2Repository = messagingV2Repository)
    call.respond(
    buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("silent", true)
put("type", msgType)
    }
)
}
}
