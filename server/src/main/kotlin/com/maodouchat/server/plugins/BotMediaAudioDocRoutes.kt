package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 媒体·音频文档：语音/音频/文档/文件读取。 */
internal fun Route.configureBotMediaAudioDocRoutes(
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

        post("/api/bot/sendDocument") {
            val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
            if (!com.maodouchat.server.service.RuntimeConfigService.isFileShareEnabled()) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("file_share_disabled"))
            }
            if (!com.maodouchat.server.service.RuntimeConfigService.isMediaUploadEnabled()) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("media_upload_disabled"))
            }
            val body = call.receiveBoundedTextOrEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val fields = when (val r = parseBotSendDocumentFields(obj)) {
                is BotSendDocumentFieldsResult.Ok -> r.fields
                BotSendDocumentFieldsResult.MissingRequired ->
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/fileBase64 required"))
            }
            val chatId = fields.chatId
            val fileName = fields.fileName
            val caption = fields.caption
            if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
            }
            val bytes = when (val r = decodeBotDocumentBytes(fields.fileBase64)) {
                is BotDocumentBytesResult.Ok -> r.bytes
                BotDocumentBytesResult.InvalidBase64 ->
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid base64"))
                BotDocumentBytesResult.TooLarge ->
                    return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("file too large (max 8MB)"))
            }
            val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            val now = System.currentTimeMillis()
            val content = buildBotDocumentContent(fileName, caption, bytes.size)
            val ok = runCatching {
                serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "FILE")
            }.getOrDefault(false)
            if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendDocument")
            val botMessage = com.maodouchat.server.model.MessageResponse(
                id = msgId, chatId = chatId, senderId = bot.id, content = content,
                type = "FILE", timestamp = now, status = "SENT"
            )
            fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage, messagingV2Repository = messagingV2Repository)
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("messageId", msgId)
    put("fileName", fileName)
    put("size", bytes.size)
            }
        )
        }

        get("/api/bot/getFile") {
            val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
            val id = parseBotGetFileId(call.request.queryParameters)
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("messageId or fileId required"))
            val msg = serviceMessageRepo.getById(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("message not found"))
            if (!conversationParticipantRepo.isParticipant(msg.chatId, bot.id)) {
                return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
            }
            val content = msg.content
            val isBotMedia = content.contains("[botFileName:") || content.contains("[botPhotoSize:") ||
                content.startsWith("📎 ") || content.startsWith("🖼 ")
            if (!isBotMedia && msg.senderId != bot.id) {
                return@get call.respond(
                    HttpStatusCode.Forbidden,
                    ErrorResponse("not a bot media envelope (E2EE peer content is not downloadable)")
                )
            }
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, msg.chatId, null, "getFile")
            call.respond(
            buildJsonObject {
    put("messageId", msg.id)
    put("chatId", msg.chatId)
    put("type", msg.type)
    put("content", content.take(4000))
    put("timestamp", msg.timestamp)
    put("note", "E2EE peer attachments are not exposed; bot plaintext media metadata only")
            }
        )
        }
}
