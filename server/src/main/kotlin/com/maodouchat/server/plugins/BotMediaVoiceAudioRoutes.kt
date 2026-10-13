package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.isMediaUploadEnabled
import com.maodouchat.server.service.isVoiceMessagesEnabled
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 媒体·音频文档：语音/音频（从 BotMediaAudioDocRoutes 按域拆出）。 */
internal fun Route.configureBotMediaVoiceAudioRoutes(
    userRepo: UserRepository,
    serviceMessageRepo: ServiceMessageRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {

    post("/api/bot/sendVoice") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isVoiceMessagesEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("voice_messages_disabled"))
        }
        if (!com.maodouchat.server.service.RuntimeConfigService.isMediaUploadEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("media_upload_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        // 9.138：与 sendPhoto/sendDocument 一致拒绝空媒体——此前可广播无内容的 voice 消息
        val fields = when (val r = parseBotSendVoiceFields(obj)) {
            is BotSendVoiceFieldsResult.Ok -> r.fields
            BotSendVoiceFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/voice required"))
        }
        val chatId = fields.chatId
        val duration = fields.durationSec
        val caption = fields.caption
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val size = measureBotVoiceSize(fields.fileBase64)
        if (size > BOT_VOICE_MAX_BYTES) {
            return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("voice too large (max 4MB)"))
        }
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val content = buildBotVoiceContent(duration, caption, size)
        val ok = runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "VOICE")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendVoice")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = content,
            type = "VOICE", timestamp = now, status = "SENT"
        )
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage, messagingV2Repository = messagingV2Repository)
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("duration", duration)
put("size", size)
put("type", "VOICE")
        }
    )
    }

    post("/api/bot/sendAudio") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isMediaUploadEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("media_upload_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val fields = when (val parsed = parseBotSendAudioFields(obj)) {
            is BotSendAudioFieldsResult.Ok -> parsed.fields
            BotSendAudioFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/audioBase64 required"))
        }
        val chatId = fields.chatId
        val title = fields.title
        val duration = fields.duration
        val caption = fields.caption
        val b64 = fields.fileBase64
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val size = decodeBotAudioSize(b64)
        if (size > BOT_AUDIO_MAX_BYTES) {
            return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("audio too large (max 10MB)"))
        }
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val content = buildBotAudioContent(title, duration, caption, size)
        val ok = runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "FILE")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendAudio")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = content,
            type = "FILE", timestamp = now, status = "SENT"
        )
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage, messagingV2Repository = messagingV2Repository)
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("size", size)
put("duration", duration)
put("type", "FILE")
        }
    )
    }
}
