package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 提示/引导：安全与提醒类端点（邀请 / 安全码 / 剧透 / 截屏保护）。 */
internal fun Route.configureBotPresentationHintSafetyRoutes(
    userRepository: UserRepository,
    participantRepository: ConversationParticipantRepository,
    serviceMessageRepository: ServiceMessageRepository,
    botRateLimiter: BoundedRateLimiter,
    json: Json,
) {

    post("/api/bot/sendInviteHint") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isGroupInvitesEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("group_invites_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val fields = when (val parsed = parseBotSendHintFields(obj, "Invite link ready")) {
            is BotSendHintFieldsResult.Ok -> parsed.fields
            BotSendHintFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        }
        val chatId = fields.chatId
        val hint = fields.hint
        if (!participantRepository.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val content = buildBotSendHintContent("INVITEHINT:", hint)
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
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendInviteHint")
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "SYSTEM")
        }
    )
    }

    post("/api/bot/sendSafetyHint") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isSafetyCodeEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("safety_code_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val fields = when (val parsed = parseBotSendHintFields(obj, "Verify safety code out-of-band")) {
            is BotSendHintFieldsResult.Ok -> parsed.fields
            BotSendHintFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        }
        val chatId = fields.chatId
        val hint = fields.hint
        if (!participantRepository.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val content = buildBotSendHintContent("🔐 ", hint)
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
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendSafetyHint")
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "SYSTEM")
        }
    )
    }

    post("/api/bot/sendSpoilerHint") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isSpoilerMediaEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("spoiler_media_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val fields = when (val parsed = parseBotSendHintFields(obj, "Spoiler media: tap to reveal")) {
            is BotSendHintFieldsResult.Ok -> parsed.fields
            BotSendHintFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        }
        val chatId = fields.chatId
        val hint = fields.hint
        if (!participantRepository.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val content = buildBotSendHintContent("🌫️ ", hint)
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
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendSpoilerHint")
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "SYSTEM")
        }
    )
    }

    post("/api/bot/sendSecretHint") {
        call.respond(HttpStatusCode.Gone, ErrorResponse("sendSecretHint_removed"))
    }

    post("/api/bot/sendSecureHint") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isScreenSecureRuntimeEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("screen_secure_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val fields = when (val parsed = parseBotSendHintFields(obj, "Screen capture protection is active")) {
            is BotSendHintFieldsResult.Ok -> parsed.fields
            BotSendHintFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        }
        val chatId = fields.chatId
        val hint = fields.hint
        if (!participantRepository.isParticipant(chatId, bot.id)) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        val content = buildBotSendHintContent("🛡️ ", hint)
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
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendSecureHint")
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "SYSTEM")
        }
    )
    }
}
