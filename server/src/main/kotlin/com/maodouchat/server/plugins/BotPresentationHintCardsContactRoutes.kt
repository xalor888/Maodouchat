package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 提示·卡片：联系人卡片（从 BotPresentationHintCardsRoutes 按域拆出）。 */
internal fun Route.configureBotPresentationHintCardsContactRoutes(
    userRepository: UserRepository,
    participantRepository: ConversationParticipantRepository,
    serviceMessageRepository: ServiceMessageRepository,
    botRateLimiter: BoundedRateLimiter,
    json: Json,
) {

post("/api/bot/sendContactCard") {
    val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
    if (!com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled()) {
        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("markdown_disabled"))
    }
    if (!com.maodouchat.server.service.RuntimeConfigService.isContactCardEnabled()) {
        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("contact_card_disabled"))
    }
    val body = call.receiveBoundedTextOrEmpty()
    val obj = call.requireJsonObjectOr400(body) ?: return@post
    val fields = when (val result = parseBotSendContactCardFields(obj)) {
        is BotSendContactCardFieldsResult.Ok -> result.fields
        BotSendContactCardFieldsResult.MissingRequired ->
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
    }
    val chatId = fields.chatId
    if (!participantRepository.isParticipant(chatId, bot.id)) {
        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
    }
    val content = buildBotContactCardContent(fields.name)
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
    com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendContactCard")
    call.respond(
    buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "MARKDOWN")
    }
)
}
}
