package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ServiceMessageRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.insert
import com.maodouchat.server.repository.logCommand
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bot Markdown 消息发送。 */
internal fun Route.configureBotMessagingSendMarkdownRoutes(
    userRepo: UserRepository,
    serviceMessageRepo: ServiceMessageRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {
post("/api/bot/sendMarkdown") {
    val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
    if (!com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled()) {
        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("markdown disabled by admin"))
    }
    val body = call.receiveBoundedTextOrEmpty()
    val obj = call.requireJsonObjectOr400(body) ?: return@post
    val fields = when (val parsed = parseBotMarkdownFields(obj)) {
        is BotMarkdownFieldsResult.Ok -> parsed.fields
        BotMarkdownFieldsResult.MissingRequired ->
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/text required"))
    }
    val chatId = fields.chatId
    val text = fields.text
    val silent = fields.silentRequested && com.maodouchat.server.service.RuntimeConfigService.isSilentSendEnabled()
    if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
    }
    val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
    val now = System.currentTimeMillis()
    val ok = runCatching {
        serviceMessageRepo.insert(msgId, chatId, bot.id, text, now, "MARKDOWN")
    }.getOrDefault(false)
    if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
    com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendMarkdown")
    val botMessage = com.maodouchat.server.model.MessageResponse(
        id = msgId, chatId = chatId, senderId = bot.id, content = text,
        type = "MARKDOWN", timestamp = now, status = "SENT"
    )
    fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage, messagingV2Repository = messagingV2Repository)
    call.respond(
    buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "MARKDOWN")
put("silent", silent)
    }
)
}
}
