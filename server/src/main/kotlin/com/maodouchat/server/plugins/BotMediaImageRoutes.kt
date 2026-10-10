package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 媒体·图片：贴纸/照片/视频/动图。 */
internal fun Route.configureBotMediaImageRoutes(
    userRepo: UserRepository,
    serviceMessageRepo: ServiceMessageRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {

        post("/api/bot/sendSticker") {
            val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
            if (!com.maodouchat.server.service.RuntimeConfigService.isStickersEnabled()) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("stickers_disabled"))
            }
            val body = call.receiveBoundedTextOrEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val fields = when (val r = parseBotSendStickerFields(obj)) {
                is BotSendStickerFieldsResult.Ok -> r.fields
                BotSendStickerFieldsResult.MissingRequired ->
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/emoji required"))
            }
            val chatId = fields.chatId
            if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
            }
            val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            val now = System.currentTimeMillis()
            val content = buildBotStickerContent(fields.emoji, fields.pack)
            val ok = runCatching {
                serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "STICKER")
            }.getOrDefault(false)
            if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendSticker")
            val botMessage = com.maodouchat.server.model.MessageResponse(
                id = msgId, chatId = chatId, senderId = bot.id, content = content,
                type = "STICKER", timestamp = now, status = "SENT"
            )
            fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage, messagingV2Repository = messagingV2Repository)
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("messageId", msgId)
    put("emoji", fields.emoji)
    put("type", "STICKER")
            }
        )
        }

        post("/api/bot/sendPhoto") {
            val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
            if (!com.maodouchat.server.service.RuntimeConfigService.isMediaUploadEnabled()) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("media_upload_disabled"))
            }
            if (!com.maodouchat.server.service.RuntimeConfigService.isImageSendEnabled()) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("image_send_disabled"))
            }
            val body = call.receiveBoundedTextOrEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val fields = when (val r = parseBotSendPhotoFields(obj)) {
                is BotSendPhotoFieldsResult.Ok -> r.fields
                BotSendPhotoFieldsResult.MissingRequired ->
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/photoBase64 required"))
            }
            val chatId = fields.chatId
            val caption = fields.caption
            if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
            }
            val bytes = decodeBotPhotoBytes(fields.fileBase64)
            if (bytes == null) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid base64"))
            }
            if (bytes.size > BOT_PHOTO_MAX_BYTES) {
                return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("photo too large (max 5MB)"))
            }
            val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            val now = System.currentTimeMillis()
            val content = buildBotPhotoContent(caption, bytes.size)
            val ok = runCatching {
                serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "IMAGE")
            }.getOrDefault(false)
            if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendPhoto")
            val botMessage = com.maodouchat.server.model.MessageResponse(
                id = msgId, chatId = chatId, senderId = bot.id, content = content,
                type = "IMAGE", timestamp = now, status = "SENT"
            )
            fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage, messagingV2Repository = messagingV2Repository)
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("messageId", msgId)
    put("size", bytes.size)
    put("type", "IMAGE")
            }
        )
        }

        post("/api/bot/sendVideo") {
            val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
            if (!com.maodouchat.server.service.RuntimeConfigService.isMediaUploadEnabled()) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("media_upload_disabled"))
            }
            val body = call.receiveBoundedTextOrEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val fields = when (val r = parseBotSendVideoFields(obj)) {
                is BotSendVideoFieldsResult.Ok -> r.fields
                BotSendVideoFieldsResult.MissingRequired ->
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/videoBase64 required"))
            }
            val chatId = fields.chatId
            val caption = fields.caption
            val duration = fields.durationSec
            // 9.138：与 sendPhoto/sendDocument 一致拒绝空媒体（parseBotSendVideoFields 已判）
            if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
            }
            val size = measureBotVideoSize(fields.fileBase64)
            if (size > BOT_VIDEO_MAX_BYTES) {
                return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("video too large (max 12MB)"))
            }
            val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            val now = System.currentTimeMillis()
            val content = buildBotVideoContent(duration, caption, size)
            val ok = runCatching {
                serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "VIDEO")
            }.getOrDefault(false)
            if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendVideo")
            val botMessage = com.maodouchat.server.model.MessageResponse(
                id = msgId, chatId = chatId, senderId = bot.id, content = content,
                type = "VIDEO", timestamp = now, status = "SENT"
            )
            fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage, messagingV2Repository = messagingV2Repository)
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("messageId", msgId)
    put("size", size)
    put("duration", duration)
    put("type", "VIDEO")
            }
        )
        }

        post("/api/bot/sendAnimation") {
            val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
            if (!com.maodouchat.server.service.RuntimeConfigService.isMediaUploadEnabled()) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("media_upload_disabled"))
            }
            val body = call.receiveBoundedTextOrEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val fields = when (val parsed = parseBotSendAnimationFields(obj)) {
                is BotSendAnimationFieldsResult.Ok -> parsed.fields
                BotSendAnimationFieldsResult.MissingRequired ->
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/animationBase64 required"))
            }
            val chatId = fields.chatId
            val caption = fields.caption
            val b64 = fields.fileBase64
            if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
            }
            val size = decodeBotAnimationSize(b64)
            if (size > BOT_ANIMATION_MAX_BYTES) {
                return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("animation too large (max 8MB)"))
            }
            val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            val now = System.currentTimeMillis()
            val content = buildBotAnimationContent(caption, size)
            val ok = runCatching {
                serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "GIF")
            }.getOrDefault(false)
            if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendAnimation")
            val botMessage = com.maodouchat.server.model.MessageResponse(
                id = msgId, chatId = chatId, senderId = bot.id, content = content,
                type = "GIF", timestamp = now, status = "SENT"
            )
            fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage, messagingV2Repository = messagingV2Repository)
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("messageId", msgId)
    put("size", size)
    put("type", "GIF")
            }
        )
        }
}
