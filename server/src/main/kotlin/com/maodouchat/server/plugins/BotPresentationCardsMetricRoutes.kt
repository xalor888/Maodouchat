package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ServiceMessageRepository
import com.maodouchat.server.repository.UserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bot 指标卡片发送。 */
internal fun Route.configureBotPresentationCardsMetricRoutes(
    userRepository: UserRepository,
    participantRepository: ConversationParticipantRepository,
    serviceMessageRepository: ServiceMessageRepository,
    botRateLimiter: BoundedRateLimiter,
    json: Json,
) {
post("/api/bot/sendMetric") {
    val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
    if (!com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled()) {
        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("markdown_disabled"))
    }
    val body = call.receiveBoundedTextOrEmpty()
    val obj = call.requireJsonObjectOr400(body) ?: return@post
    val fields = when (val parsed = parseBotMetricCompareFields(
        obj,
        "label", "metric", 40,
        "value", "0", 40,
        "unit", "", 20,
    )) {
        is BotMetricCompareFieldsResult.Ok -> parsed.fields
        BotMetricCompareFieldsResult.MissingRequired ->
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
    }
    val chatId = fields.chatId
    if (!participantRepository.isParticipant(chatId, bot.id)) {
        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
    }
    val content = buildBotMetricContent(fields.first, fields.second, fields.third)
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
    com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendMetric")
    call.respond(
    buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "MARKDOWN")
    }
)
}
}
