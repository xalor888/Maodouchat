package com.maodouchat.server.plugins

import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Bot 展示组件（自 BotPresentationRouting.kt 拆分，B12）。 */
internal fun Route.configureBotPresentationWidgetsRoutes(
    userRepository: UserRepository,
    participantRepository: ConversationParticipantRepository,
    serviceMessageRepository: ServiceMessageRepository,
    botRateLimiter: BoundedRateLimiter,
    json: Json,
) {

    get("/api/bot/ping") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "ping")
        call.respond(
        buildJsonObject {
put("ok", true)
put("botId", bot.id)
put("ts", System.currentTimeMillis())
        }
    )
    }

    post("/api/bot/sendHr") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("markdown disabled by admin"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val fields = when (val parsed = parseBotSendHrFields(obj)) {
            is BotSendHrFieldsResult.Ok -> parsed.fields
            BotSendHrFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        }
        val chatId = fields.chatId
        val note = fields.note
        if (!participantRepository.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val content = buildBotHrContent(note)
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
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendHr")
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "MARKDOWN")
        }
    )
    }



    post("/api/bot/sendStatus") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val fields = when (val parsed = parseBotSendStatusFields(obj)) {
            is BotSendStatusFieldsResult.Ok -> parsed.fields
            BotSendStatusFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/text required"))
        }
        val chatId = fields.chatId
        val text = fields.text
        if (!participantRepository.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val content = "STATUS: $text"
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
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendStatus")
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "SYSTEM")
        }
    )
    }

    get("/api/bot/getMyStats") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
        val chatCount = participantRepository.chatIdsForUser(bot.id).size
        val pending = com.maodouchat.server.repository.BotRepository.countPendingUpdates(bot.id)
        val cmds = com.maodouchat.server.repository.BotRepository.getMyCommands(bot.id)
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "getMyStats")
        call.respond(
        buildJsonObject {
put("ok", true)
put("botId", bot.id)
put("enabled", bot.enabled)
put("chatCount", chatCount)
put("pendingUpdateCount", pending)
put("commandCount", cmds.size)
put("webhookConfigured", !bot.webhookUrl.isNullOrBlank())
        }
    )
    }

    post("/api/bot/sendTable") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("markdown disabled by admin"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val fields = when (val parsed = parseBotSendTableFields(obj)) {
            is BotSendTableFieldsResult.Ok -> parsed.fields
            BotSendTableFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/headers/rows required"))
        }
        val chatId = fields.chatId
        val headers = fields.headers
        val rows = fields.rows
        if (!participantRepository.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val content = buildBotTableContent(headers, rows)
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
                content = content.take(4000),
                timestamp = now,
                type = "MARKDOWN",
            )
        }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendTable")
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "MARKDOWN")
put("rows", rows.size)
        }
    )
    }



    post("/api/bot/sendBadge") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("markdown disabled by admin"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val fields = when (val parsed = parseBotSendBadgeFields(obj)) {
            is BotSendBadgeFieldsResult.Ok -> parsed.fields
            BotSendBadgeFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        }
        val chatId = fields.chatId
        val label = fields.label
        val value = fields.value
        if (!participantRepository.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val content = buildBotBadgeContent(label, value)
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
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendBadge")
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "MARKDOWN")
        }
    )
    }
}
