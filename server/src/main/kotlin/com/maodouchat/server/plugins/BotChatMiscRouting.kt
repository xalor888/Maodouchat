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
import org.jetbrains.exposed.sql.*

/** Bot 会话 ID/命令/星标/清单（自 BotCoreRouting.kt 拆分，B12）。 */
internal fun Route.configureBotChatMiscRoutes(
    userRepo: UserRepository,
    serviceMessageRepo: ServiceMessageRepository,
    starMessageRepo: StarMessageRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {

    get("/api/bot/getChatIds") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val chats = org.jetbrains.exposed.sql.transactions.transaction {
            com.maodouchat.server.db.ChatParticipants.selectAll()
                .where { com.maodouchat.server.db.ChatParticipants.userId eq bot.id }
                .map { it[com.maodouchat.server.db.ChatParticipants.chatId] }
                .distinct()
        }
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "getChatIds")
        call.respond(
        buildJsonObject {
put("ok", true)
put("chatIds", Json.parseToJsonElement(Json.encodeToString(chats)))
put("count", chats.size)
        }
    )
    }

    post("/api/bot/clearCommands") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val cleared = com.maodouchat.server.repository.BotRepository.setMyCommands(bot.id, emptyList())
            ?: return@post call.respondBotUnavailable()
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "clearCommands")
        call.respond(
            buildJsonObject {
                put("ok", true)
                put("commands", Json.parseToJsonElement(Json.encodeToString(cleared)))
                put("count", cleared.size)
            }
        )
    }

    post("/api/bot/starMessage") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isMessageStarringEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("starring_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val messageId = obj["messageId"]?.jsonPrimitive?.content.orEmpty()
        if (messageId.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("messageId required"))
        }
        val msg = serviceMessageRepo.metadata(messageId)
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("message not found"))
        if (!conversationParticipantRepo.isParticipant(msg.chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val starred = starMessageRepo.toggleStar(
            userId = bot.id,
            messageId = messageId,
            requireBotDeliverable = true
        )
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("cannot star"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, msg.chatId, messageId, "starMessage")
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", messageId)
put("starred", starred)
        }
    )
    }

    post("/api/bot/sendChecklist") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("markdown disabled by admin"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val title = obj["title"]?.jsonPrimitive?.content.orEmpty().take(80)
        val itemsEl = obj["items"] as? kotlinx.serialization.json.JsonArray
        val items = itemsEl?.mapNotNull {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.take(80)
        }?.filter { it.isNotBlank() }?.take(20).orEmpty()
        if (chatId.isBlank() || items.isEmpty()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/items required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val head = if (title.isNotBlank()) "**$title**\n" else ""
        val bodyMd = items.joinToString("\n") { "- [ ] $it" }
        val content = head + bodyMd
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val ok = runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "MARKDOWN")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendChecklist")
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
put("items", items.size)
        }
    )
    }
}
