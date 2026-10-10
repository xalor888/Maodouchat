package com.maodouchat.server.plugins

import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 消息变体·发送：静默/ Markdown / 戳一戳。 */
internal fun Route.configureBotMessagingSendVariantsRoutes(
    userRepo: UserRepository,
    serviceMessageRepo: ServiceMessageRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {

        post("/api/bot/sendMessageSilent") {
            // Convenience wrapper: force silent when runtime allows
            val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
            if (!com.maodouchat.server.service.RuntimeConfigService.isSilentSendEnabled()) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("silent_send_disabled"))
            }
            val body = call.receiveBoundedTextOrEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val fields = when (val parsed = parseBotSendMessageSilentFields(obj)) {
                is BotSendMessageSilentFieldsResult.Ok -> parsed.fields
                BotSendMessageSilentFieldsResult.MissingRequired ->
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/text required"))
            }
            val chatId = fields.chatId
            val text = fields.text
            val msgType = fields.msgType
            if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
            }
            val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            val now = System.currentTimeMillis()
            val contentOut = text
            val ok = runCatching {
                serviceMessageRepo.insert(msgId, chatId, bot.id, contentOut, now, msgType)
            }.getOrDefault(false)
            if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendMessageSilent")
            val botMessage = com.maodouchat.server.model.MessageResponse(
                id = msgId, chatId = chatId, senderId = bot.id, content = contentOut,
                type = msgType, timestamp = now, status = "SENT"
            )
            // silent: still deliver WS, but clients should suppress push (server push path checks silent if present)
            fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage, messagingV2Repository = messagingV2Repository)
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("messageId", msgId)
    put("silent", true)
    put("type", msgType)
            }
        )
        }

        post("/api/bot/sendMarkdown") {
            val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
            if (!com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled()) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("markdown disabled by admin"))
            }
            val body = call.receiveBoundedTextOrEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val fields = when (val parsed = parseBotMarkdownFields(obj)) {
                is BotMarkdownFieldsResult.Ok -> parsed.fields
                BotMarkdownFieldsResult.MissingRequired ->
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/text required"))
            }
            val chatId = fields.chatId
            val text = fields.text
            val silent = fields.silentRequested && com.maodouchat.server.service.RuntimeConfigService.isSilentSendEnabled()
            if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
            }
            val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            val now = System.currentTimeMillis()
            val ok = runCatching {
                serviceMessageRepo.insert(msgId, chatId, bot.id, text, now, "MARKDOWN")
            }.getOrDefault(false)
            if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendMarkdown")
            val botMessage = com.maodouchat.server.model.MessageResponse(
                id = msgId, chatId = chatId, senderId = bot.id, content = text,
                type = "MARKDOWN", timestamp = now, status = "SENT"
            )
            fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage, messagingV2Repository = messagingV2Repository)
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("messageId", msgId)
    put("type", "MARKDOWN")
    put("silent", silent)
            }
        )
        }

        post("/api/bot/sendNudge") {
            val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
            if (!com.maodouchat.server.service.RuntimeConfigService.isNudgeEnabled()) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("nudge_disabled"))
            }
            val body = call.receiveBoundedTextOrEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val fields = when (val result = parseBotSendNudgeFields(obj)) {
                is BotSendNudgeFieldsResult.Ok -> result.fields
                BotSendNudgeFieldsResult.MissingRequired ->
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
            }
            val chatId = fields.chatId
            if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
            }
            val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            val now = System.currentTimeMillis()
            val content = buildBotNudgeContent(fields.note)
            val ok = runCatching {
                serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "NUDGE")
            }.getOrDefault(false)
            if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendNudge")
            val botMessage = com.maodouchat.server.model.MessageResponse(
                id = msgId, chatId = chatId, senderId = bot.id, content = content,
                type = "NUDGE", timestamp = now, status = "SENT"
            )
            fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage, messagingV2Repository = messagingV2Repository)
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("messageId", msgId)
    put("type", "NUDGE")
            }
        )
        }
}
