package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.isSafetyCodeEnabled
import com.maodouchat.server.service.isSpoilerMediaEnabled
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.server.routing.post
import kotlinx.serialization.json.*

/** Bot 提示：内容安全（安全码 / 剧透媒体提示；从 BotPresentationHintSafetyRoutes 按域拆出）。 */
internal fun Route.configureBotPresentationHintSafetyContentRoutes(
    userRepository: UserRepository,
    participantRepository: ConversationParticipantRepository,
    serviceMessageRepository: ServiceMessageRepository,
    botRateLimiter: BoundedRateLimiter,
    json: Json,
) {

post("/api/bot/sendSafetyHint") {
    val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
    if (!com.maodouchat.server.service.RuntimeConfigService.isSafetyCodeEnabled()) {
        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("safety_code_disabled"))
    }
    val body = call.receiveBoundedTextOrEmpty()
    val obj = call.requireJsonObjectOr400(body) ?: return@post
    val fields = when (val parsed = parseBotSendHintFields(obj, "Verify safety code out-of-band")) {
        is BotSendHintFieldsResult.Ok -> parsed.fields
        BotSendHintFieldsResult.MissingRequired ->
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
    }
    val chatId = fields.chatId
    val hint = fields.hint
    if (!participantRepository.isParticipant(chatId, bot.id)) {
        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
    }
    val content = buildBotSendHintContent("🔐 ", hint)
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
    com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendSafetyHint")
    call.respond(
    buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "SYSTEM")
    }
)
}

post("/api/bot/sendSpoilerHint") {
    val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
    if (!com.maodouchat.server.service.RuntimeConfigService.isSpoilerMediaEnabled()) {
        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("spoiler_media_disabled"))
    }
    val body = call.receiveBoundedTextOrEmpty()
    val obj = call.requireJsonObjectOr400(body) ?: return@post
    val fields = when (val parsed = parseBotSendHintFields(obj, "Spoiler media: tap to reveal")) {
        is BotSendHintFieldsResult.Ok -> parsed.fields
        BotSendHintFieldsResult.MissingRequired ->
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
    }
    val chatId = fields.chatId
    val hint = fields.hint
    if (!participantRepository.isParticipant(chatId, bot.id)) {
        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
    }
    val content = buildBotSendHintContent("🌫️ ", hint)
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
    com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendSpoilerHint")
    call.respond(
    buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "SYSTEM")
    }
)
}
}
