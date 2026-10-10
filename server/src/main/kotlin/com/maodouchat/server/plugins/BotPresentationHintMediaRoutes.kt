package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 提示/引导：媒体与分享类端点（二维码 / 文件 / 位置 / 照片 / 水印）。 */
internal fun Route.configureBotPresentationHintMediaRoutes(
    userRepository: UserRepository,
    participantRepository: ConversationParticipantRepository,
    serviceMessageRepository: ServiceMessageRepository,
    botRateLimiter: BoundedRateLimiter,
    json: Json,
) {

    post("/api/bot/sendQrHint") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isQrCodeEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("qr_code_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val fields = when (val parsed = parseBotSendHintFields(obj, "Scan my QR to connect")) {
            is BotSendHintFieldsResult.Ok -> parsed.fields
            BotSendHintFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        }
        val chatId = fields.chatId
        val hint = fields.hint
        if (!participantRepository.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val content = buildBotSendHintContent("📷 ", hint)
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
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendQrHint")
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "SYSTEM")
        }
    )
    }

    post("/api/bot/sendDownloadHint") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isAutoDownloadEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("auto_download_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val fields = when (val parsed = parseBotSendHintFields(obj, "Auto-download is on for this network")) {
            is BotSendHintFieldsResult.Ok -> parsed.fields
            BotSendHintFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        }
        val chatId = fields.chatId
        val hint = fields.hint
        if (!participantRepository.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val content = buildBotSendHintContent("⬇️ ", hint)
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
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendDownloadHint")
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "SYSTEM")
        }
    )
    }

    post("/api/bot/sendLocationHint") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isStaticLocationEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("static_location_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val fields = when (val parsed = parseBotSendHintFields(obj, "Share a static pin")) {
            is BotSendHintFieldsResult.Ok -> parsed.fields
            BotSendHintFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        }
        val chatId = fields.chatId
        val hint = fields.hint
        if (!participantRepository.isParticipant(chatId, bot.id)) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        val content = buildBotSendHintContent("📍 ", hint)
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
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendLocationHint")
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "SYSTEM")
        }
    )
    }

    post("/api/bot/sendFileHint") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isFileShareEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("file_share_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val fields = when (val parsed = parseBotSendHintFields(obj, "File share is available")) {
            is BotSendHintFieldsResult.Ok -> parsed.fields
            BotSendHintFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        }
        val chatId = fields.chatId
        val hint = fields.hint
        if (!participantRepository.isParticipant(chatId, bot.id)) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        val content = buildBotSendHintContent("📎 ", hint)
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
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendFileHint")
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "SYSTEM")
        }
    )
    }

    post("/api/bot/sendPhotoHint") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isImageSendEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("image_send_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val fields = when (val parsed = parseBotSendHintFields(obj, "Photo send is available")) {
            is BotSendHintFieldsResult.Ok -> parsed.fields
            BotSendHintFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        }
        val chatId = fields.chatId
        val hint = fields.hint
        if (!participantRepository.isParticipant(chatId, bot.id)) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        val content = buildBotSendHintContent("🖼️ ", hint)
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
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendPhotoHint")
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "SYSTEM")
        }
    )
    }

    post("/api/bot/sendVideoHint") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isVideoSendEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("video_send_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val fields = when (val parsed = parseBotSendHintFields(obj, "Video send is available")) {
            is BotSendHintFieldsResult.Ok -> parsed.fields
            BotSendHintFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        }
        val chatId = fields.chatId
        val hint = fields.hint
        if (!participantRepository.isParticipant(chatId, bot.id)) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        val content = buildBotSendHintContent("🎬 ", hint)
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
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendVideoHint")
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "SYSTEM")
        }
    )
    }

    post("/api/bot/sendGifHint") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isGifSendEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("gif_send_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val fields = when (val parsed = parseBotSendHintFields(obj, "GIF send can be toggled separately from images")) {
            is BotSendHintFieldsResult.Ok -> parsed.fields
            BotSendHintFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        }
        val chatId = fields.chatId
        val hint = fields.hint
        if (!participantRepository.isParticipant(chatId, bot.id)) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        val content = buildBotSendHintContent("🎞️ ", hint)
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
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendGifHint")
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "SYSTEM")
        }
    )
    }

    post("/api/bot/sendWatermarkHint") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isBlindWatermarkEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("blind_watermark_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val fields = when (val parsed = parseBotSendHintFields(obj, "Blind watermarks embed user id + time for leak forensics")) {
            is BotSendHintFieldsResult.Ok -> parsed.fields
            BotSendHintFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        }
        val chatId = fields.chatId
        val hint = fields.hint
        if (!participantRepository.isParticipant(chatId, bot.id)) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        val content = buildBotSendHintContent("🔏 ", hint)
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
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendWatermarkHint")
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "SYSTEM")
        }
    )
    }
}
