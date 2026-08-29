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
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

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
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val note = obj["text"]?.jsonPrimitive?.content.orEmpty().take(200)
        if (chatId.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        }
        if (!participantRepository.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val content = if (note.isNotBlank()) "---\n$note\n---" else "---"
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
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val text = (obj["text"] ?: obj["status"])?.jsonPrimitive?.content.orEmpty().take(200)
        if (chatId.isBlank() || text.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/text required"))
        }
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
        val chatCount = org.jetbrains.exposed.sql.transactions.transaction {
            com.maodouchat.server.db.ChatParticipants.selectAll()
                .where { com.maodouchat.server.db.ChatParticipants.userId eq bot.id }
                .count()
        }
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
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val headersEl = obj["headers"] as? kotlinx.serialization.json.JsonArray
        val rowsEl = obj["rows"] as? kotlinx.serialization.json.JsonArray
        val headers = headersEl?.mapNotNull {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.take(40)
        }?.filter { it.isNotBlank() }?.take(8).orEmpty()
        val rows = rowsEl?.mapNotNull { rowEl ->
            val arr = rowEl as? kotlinx.serialization.json.JsonArray ?: return@mapNotNull null
            arr.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.take(40) }
                .take(8)
        }?.filter { it.isNotEmpty() }?.take(20).orEmpty()
        if (chatId.isBlank() || headers.isEmpty() || rows.isEmpty()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/headers/rows required"))
        }
        if (!participantRepository.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val headLine = "| " + headers.joinToString(" | ") + " |"
        val sepLine = "| " + headers.joinToString(" | ") { "---" } + " |"
        val bodyLines = rows.joinToString("\n") { r ->
            val cells = (0 until headers.size).map { i -> r.getOrNull(i).orEmpty() }
            "| " + cells.joinToString(" | ") + " |"
        }
        val content = headLine + "\n" + sepLine + "\n" + bodyLines
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
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val label = obj["label"]?.jsonPrimitive?.content.orEmpty().ifBlank { "badge" }.take(40)
        val value = obj["value"]?.jsonPrimitive?.content.orEmpty().take(80)
        if (chatId.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        if (!participantRepository.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val content = "**$label**: `$value`"
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
