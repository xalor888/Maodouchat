package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.isMarkdownEnabled
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.server.routing.post
import kotlinx.serialization.json.*

/** Bot 状态推送：分隔线（视觉分隔；从 BotPresentationStatusPushRoutes 按域拆出）。 */
internal fun Route.configureBotPresentationStatusPushDividerRoutes(
    userRepository: UserRepository,
    participantRepository: ConversationParticipantRepository,
    serviceMessageRepository: ServiceMessageRepository,
    botRateLimiter: BoundedRateLimiter,
    json: Json,
) {

post("/api/bot/sendDivider") {
    val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
    if (!com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled()) {
        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("markdown disabled by admin"))
    }
    val body = call.receiveBoundedTextOrEmpty()
    val obj = call.requireJsonObjectOr400(body) ?: return@post
    val fields = when (val parsed = parseBotSendDividerFields(obj)) {
        is BotSendDividerFieldsResult.Ok -> parsed.fields
        BotSendDividerFieldsResult.MissingRequired ->
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
    }
    val chatId = fields.chatId
    val label = fields.label
    if (!participantRepository.isParticipant(chatId, bot.id)) {
        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
    }
    val content = buildBotDividerContent(label)
    val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
    val now = System.currentTimeMillis()
    val botMessage = runCatching {
        publishBotServiceMessage(
            userRepository = userRepository,
            participantRepository = participantRepository,
            serviceMessageRepository = serviceMessageRepository,
            json = json,
            botId = bot.id,
            chatId = chatId,
            messageId = msgId,
            content = content,
            timestamp = now,
            type = "MARKDOWN",
        )
    }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
    com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendDivider")
    call.respond(
    buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "MARKDOWN")
    }
)
}
}
