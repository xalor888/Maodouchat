package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 消息转发与复制（forwardMessage / copyMessage）。 */
internal fun Route.configureBotMessageForwardingRoutes(
    userRepo: UserRepository,
    serviceMessageRepo: ServiceMessageRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {

    post("/api/bot/forwardMessage") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isMessageForwardingEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forwarding_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val fromChatId = (obj["fromChatId"] ?: obj["from_chat_id"])?.jsonPrimitive?.content.orEmpty()
        val toChatId = (obj["chatId"] ?: obj["toChatId"] ?: obj["to_chat_id"])?.jsonPrimitive?.content.orEmpty()
        val messageId = (obj["messageId"] ?: obj["message_id"])?.jsonPrimitive?.content.orEmpty()
        if (fromChatId.isBlank() || toChatId.isBlank() || messageId.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("fromChatId/chatId/messageId required"))
        }
        if (!conversationParticipantRepo.isParticipant(fromChatId, bot.id) || !conversationParticipantRepo.isParticipant(toChatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot must be in both chats"))
        }
        val src = serviceMessageRepo.getById(messageId)
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("message not found"))
        if (src.chatId != fromChatId) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("message not in fromChatId"))
        }
        // Bot plaintext channel only — refuse E2EE ciphertext / attachment blobs.
        val t = src.type.uppercase()
        if (t !in setOf("TEXT", "MARKDOWN", "SYSTEM")) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("only TEXT/MARKDOWN forward supported for bots"))
        }
        val content = src.content
        if (content.contains("ENC:") || content.startsWith("SK:") || content.length > 4000) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("cannot forward encrypted content"))
        }
        val newId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val forwardedBody = content + "\n[forwardedFrom:" + fromChatId + ":" + messageId + "]"
        val ok = runCatching {
            serviceMessageRepo.insert(newId, toChatId, bot.id, forwardedBody, now, if (t == "MARKDOWN") "MARKDOWN" else "TEXT")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("forward failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, toChatId, messageId, "forwardMessage")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = newId, chatId = toChatId, senderId = bot.id, content = forwardedBody,
            type = if (t == "MARKDOWN") "MARKDOWN" else "TEXT", timestamp = now, status = "SENT"
        )
        val forwardParticipants = conversationParticipantRepo.participantIds(toChatId)
        val sourceBlockedIds = try {
            userRepo.blockedEitherWayIdsInTx(src.senderId, forwardParticipants)
        } catch (_: Exception) { emptySet() }
        fanoutBotMessage(
            userRepo,
            conversationParticipantRepo,
            json,
            bot.id,
            toChatId,
            botMessage,
            excludedRecipientIds = sourceBlockedIds,
            messagingV2Repository = messagingV2Repository,
        )
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", newId)
put("fromChatId", fromChatId)
put("chatId", toChatId)
        }
    )
    }

    post("/api/bot/copyMessage") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isMessageForwardingEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forwarding_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val fromChatId = (obj["fromChatId"] ?: obj["from_chat_id"])?.jsonPrimitive?.content.orEmpty()
        val toChatId = (obj["chatId"] ?: obj["toChatId"] ?: obj["to_chat_id"])?.jsonPrimitive?.content.orEmpty()
        val messageId = (obj["messageId"] ?: obj["message_id"])?.jsonPrimitive?.content.orEmpty()
        if (fromChatId.isBlank() || toChatId.isBlank() || messageId.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("fromChatId/chatId/messageId required"))
        }
        if (!conversationParticipantRepo.isParticipant(fromChatId, bot.id) || !conversationParticipantRepo.isParticipant(toChatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot must be in both chats"))
        }
        val src = serviceMessageRepo.getById(messageId)
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("message not found"))
        if (src.chatId != fromChatId) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("message not in fromChatId"))
        }
        val t = src.type.uppercase()
        if (t !in setOf("TEXT", "MARKDOWN", "SYSTEM")) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("only TEXT/MARKDOWN copy supported for bots"))
        }
        val content = stripInlineMeta(src.content)
        if (content.contains("ENC:") || content.startsWith("SK:") || content.length > 4000) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("cannot copy encrypted content"))
        }
        val newId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val ok = runCatching {
            serviceMessageRepo.insert(newId, toChatId, bot.id, content, now, if (t == "MARKDOWN") "MARKDOWN" else "TEXT")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("copy failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, toChatId, messageId, "copyMessage")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = newId, chatId = toChatId, senderId = bot.id, content = content,
            type = if (t == "MARKDOWN") "MARKDOWN" else "TEXT", timestamp = now, status = "SENT"
        )
        val copyParticipants = conversationParticipantRepo.participantIds(toChatId)
        val sourceBlockedIds = try {
            userRepo.blockedEitherWayIdsInTx(src.senderId, copyParticipants)
        } catch (_: Exception) { emptySet() }
        fanoutBotMessage(
            userRepo,
            conversationParticipantRepo,
            json,
            bot.id,
            toChatId,
            botMessage,
            excludedRecipientIds = sourceBlockedIds,
            messagingV2Repository = messagingV2Repository,
        )
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", newId)
put("fromChatId", fromChatId)
put("chatId", toChatId)
        }
    )
    }
}
