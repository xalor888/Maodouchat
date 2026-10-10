package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 提示·视觉：照片/视频/动图/水印。 */
internal fun Route.configureBotPresentationHintVisualRoutes(
    userRepository: UserRepository,
    participantRepository: ConversationParticipantRepository,
    serviceMessageRepository: ServiceMessageRepository,
    botRateLimiter: BoundedRateLimiter,
    json: Json,
) {

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
