package com.maodouchat.server.plugins

import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.BlobStore
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 代码/回应/引用消息（自 BotCoreRouting.kt 拆分，B12）。 */
internal fun Route.configureBotReactionRoutes(
    userRepo: UserRepository,
    serviceMessageRepo: ServiceMessageRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {

    get("/api/bot/getCommandStats") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
        val logs = com.maodouchat.server.repository.BotRepository.listCommandLogs(bot.id, limit)
        val counts = linkedMapOf<String, Int>()
        logs.forEach { row ->
            val c = row.command.ifBlank { "?" }
            counts[c] = (counts[c] ?: 0) + 1
        }
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "getCommandStats")
        call.respond(
        buildJsonObject {
put("ok", true)
put("botId", bot.id)
put("totalSampled", logs.size)
put("byCommand", Json.parseToJsonElement(Json.encodeToString(counts)))
put("recent", buildJsonArray {
    logs.take(20).forEach {
add(buildJsonObject {
    put("id", it.id)
    put("command", it.command)
    put("chatId", (it.chatId ?: ""))
    put("createdAt", it.createdAt)
})
    }
})
        }
    )
    }

    post("/api/bot/sendCode") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("markdown disabled by admin"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val code = (obj["code"] ?: obj["text"])?.jsonPrimitive?.content.orEmpty().take(3500)
        val lang = obj["language"]?.jsonPrimitive?.content.orEmpty().take(24)
        if (chatId.isBlank() || code.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/code required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val fenced = if (lang.isNotBlank()) "```$lang\n$code\n```" else "```\n$code\n```"
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val ok = runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, fenced, now, "MARKDOWN")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendCode")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = fenced,
            type = "MARKDOWN", timestamp = now, status = "SENT"
        )
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage, messagingV2Repository = messagingV2Repository)
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "MARKDOWN")
        }
    )
    }










    post("/api/bot/setMessageReaction") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isReactionsEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("reactions_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val messageId = obj["messageId"]?.jsonPrimitive?.content.orEmpty()
        val emoji = obj["emoji"]?.jsonPrimitive?.content.orEmpty().trim()
        if (messageId.isBlank() || emoji.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("messageId/emoji required"))
        }
        if (emoji !in ALLOWED_REACTION_EMOJIS) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("unsupported emoji"))
        }
        val msg = serviceMessageRepo.metadata(messageId)
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("message not found"))
        if (!conversationParticipantRepo.isParticipant(msg.chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val botReactions = serviceMessageRepo.setReaction(messageId, bot.id, emoji)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("cannot react"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, msg.chatId, messageId, "setMessageReaction")
        fanoutBotEvent(
            userRepo = userRepo,
            participantRepository = conversationParticipantRepo,
            json = json,
            botId = bot.id,
            chatId = msg.chatId,
            event = com.maodouchat.server.messaging.v2.ServiceMessagingV2Event(
                action = "REACTION_SET",
                targetMessageId = messageId,
                reactionEmoji = emoji,
            ),
            messagingV2Repository = messagingV2Repository,
        )
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", messageId)
put("emoji", emoji)
put("reactions", Json.parseToJsonElement(Json.encodeToString(botReactions)))
        }
    )
    }

    post("/api/bot/sendQuote") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("markdown disabled by admin"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val quote = (obj["quote"] ?: obj["text"])?.jsonPrimitive?.content.orEmpty().take(1500)
        val note = obj["note"]?.jsonPrimitive?.content.orEmpty().take(500)
        if (chatId.isBlank() || quote.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/quote required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val quoted = quote.lines().joinToString("\n") { "> " + it }
        val content = if (note.isNotBlank()) "$quoted\n\n$note" else quoted
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val ok = runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "MARKDOWN")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendQuote")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = content,
            type = "MARKDOWN", timestamp = now, status = "SENT"
        )
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage, messagingV2Repository = messagingV2Repository)
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "MARKDOWN")
        }
    )
    }
}
