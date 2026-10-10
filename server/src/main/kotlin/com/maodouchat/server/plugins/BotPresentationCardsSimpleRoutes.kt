package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
/** Bot 轻卡片：echo 探针、toast、键值、通知。 */
internal fun Route.configureBotPresentationCardsSimpleRoutes(
    userRepository: UserRepository,
    participantRepository: ConversationParticipantRepository,
    serviceMessageRepository: ServiceMessageRepository,
    botRateLimiter: BoundedRateLimiter,
    json: Json,
) {

    post("/api/bot/echo") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val text = parseBotEchoFields(obj).text
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "echo")
        call.respond(
        buildJsonObject {
put("ok", true)
put("botId", bot.id)
put("echo", text)
put("serverTime", System.currentTimeMillis())
        }
    )
    }

    post("/api/bot/sendToast") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val fields = when (val parsed = parseBotSendToastFields(obj)) {
            is BotSendToastFieldsResult.Ok -> parsed.fields
            BotSendToastFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/text required"))
        }
        val chatId = fields.chatId
        val text = fields.text
        if (!participantRepository.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val content = buildBotToastContent(text)
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
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendToast")
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "SYSTEM")
        }
    )
    }

    post("/api/bot/sendKeyValue") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("markdown disabled by admin"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val fields = when (val parsed = parseBotKeyValueFields(obj)) {
            is BotKeyValueFieldsResult.Ok -> parsed.fields
            BotKeyValueFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        }
        val chatId = fields.chatId
        if (!participantRepository.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val content = buildBotKeyValueContent(fields.key, fields.value)
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
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendKeyValue")
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "MARKDOWN")
        }
    )
    }

    post("/api/bot/sendNotice") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val fields = when (val parsed = parseBotSendNoticeFields(obj)) {
            is BotSendNoticeFieldsResult.Ok -> parsed.fields
            BotSendNoticeFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/text required"))
        }
        val chatId = fields.chatId
        val text = fields.text
        if (!participantRepository.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val content = buildBotNoticeContent(text)
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
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendNotice")
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "SYSTEM")
        }
    )
    }

}
