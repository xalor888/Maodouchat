package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.server.routing.post
import kotlinx.serialization.json.*

/** Bot 状态推送：告警与提醒（通知型状态推送；从 BotPresentationStatusPushRoutes 按域拆出）。 */
internal fun Route.configureBotPresentationStatusPushNotifyRoutes(
    userRepository: UserRepository,
    participantRepository: ConversationParticipantRepository,
    serviceMessageRepository: ServiceMessageRepository,
    botRateLimiter: BoundedRateLimiter,
    json: Json,
) {

post("/api/bot/sendAlert") {
    val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
    val body = call.receiveBoundedTextOrEmpty()
    val obj = call.requireJsonObjectOr400(body) ?: return@post
    val fields = when (val parsed = parseBotSendAlertFields(obj)) {
        is BotSendAlertFieldsResult.Ok -> parsed.fields
        BotSendAlertFieldsResult.MissingRequired ->
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/text required"))
    }
    val chatId = fields.chatId
    val text = fields.text
    val level = fields.level
    if (!participantRepository.isParticipant(chatId, bot.id)) {
        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
    }
    val content = buildBotAlertContent(text, level)
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
            type = "SYSTEM",
        )
    }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
    com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendAlert")
    call.respond(
    buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "SYSTEM")
put("level", fields.level)
    }
)
}

post("/api/bot/sendRemind") {
    val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
    val body = call.receiveBoundedTextOrEmpty()
    val obj = call.requireJsonObjectOr400(body) ?: return@post
    val fields = when (val parsed = parseBotSendRemindFields(obj)) {
        is BotSendRemindFieldsResult.Ok -> parsed.fields
        BotSendRemindFieldsResult.MissingRequired ->
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/text required"))
    }
    val chatId = fields.chatId
    if (!participantRepository.isParticipant(chatId, bot.id)) {
        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
    }
    val content = buildBotRemindContent(fields.text)
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
            type = "SYSTEM",
        )
    }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
    com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendRemind")
    call.respond(
    buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "SYSTEM")
    }
)
}
}
