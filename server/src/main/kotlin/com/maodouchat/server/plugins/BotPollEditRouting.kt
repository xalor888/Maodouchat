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

/** Bot 投票操作与消息编辑（自 BotCoreRouting.kt 拆分，B12）。 */
internal fun Route.configureBotPollEditRoutes(
    userRepo: UserRepository,
    serviceMessageRepo: ServiceMessageRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {

    get("/api/bot/health") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val pending = com.maodouchat.server.repository.BotRepository.countPendingUpdates(bot.id)
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "health")
        call.respond(
        buildJsonObject {
put("ok", bot.enabled)
put("botId", bot.id)
put("enabled", bot.enabled)
put("pendingUpdateCount", pending)
put("webhookConfigured", !bot.webhookUrl.isNullOrBlank())
put("serverTime", System.currentTimeMillis())
        }
    )
    }

    get("/api/bot/getMe") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val commands = com.maodouchat.server.repository.BotRepository.getMyCommands(bot.id)
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "getMe")
        call.respond(
        buildJsonObject {
put("ok", true)
put("id", bot.id)
put("name", bot.name)
put("username", bot.username)
put("description", (bot.description ?: ""))
put("enabled", bot.enabled)
put("webhookUrl", (bot.webhookUrl ?: ""))
putJsonElement("commands", commands)
put("markdownEnabled", com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled())
put("typingIndicatorsEnabled", com.maodouchat.server.service.RuntimeConfigService.isTypingIndicatorsEnabled())
put("mediaUploadEnabled", com.maodouchat.server.service.RuntimeConfigService.isMediaUploadEnabled())
        }
    )
    }

    post("/api/bot/stopPoll") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.repository.BotRepository.isBotDeliverable(bot.id)) {
            return@post call.respondBotUnavailable()
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val pollId = when (val parsed = parseBotStopPollFields(obj)) {
            is BotStopPollFieldsResult.Ok -> parsed.fields.pollId
            BotStopPollFieldsResult.Invalid ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("pollId required"))
        }
        val poll = com.maodouchat.server.repository.PollRepository.closePoll(
            pollId = pollId,
            userId = bot.id,
            requireBotDeliverable = true
        )
            ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("stop failed (creator only)"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, poll.chatId, pollId, "stopPoll")
        call.respond(
        buildJsonObject {
put("ok", true)
putJsonElement("poll", poll)
put("alias", "closePoll")
        }
    )
    }

    post("/api/bot/editMessageCaption") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val fields = when (val parsed = parseBotEditMessageCaptionFields(obj)) {
            is BotEditMessageCaptionFieldsResult.Ok -> parsed.fields
            BotEditMessageCaptionFieldsResult.MissingMessageId ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("messageId required"))
        }
        val messageId = fields.messageId
        val caption = fields.caption
        val existing = serviceMessageRepo.getById(messageId)
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("message not found"))
        if (existing.senderId != bot.id) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("not your message"))
        }
        if (!conversationParticipantRepo.isParticipant(existing.chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        // Bot plaintext cards only — refuse peer E2EE envelopes (ciphertext bodies).
        val body0 = existing.content.orEmpty()
        if (isPeerE2eeContent(body0)) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("cannot edit peer E2EE message"))
        }
        val newBody = buildBotEditCaptionContent(body0, caption)
        val editedAt = System.currentTimeMillis()
        // Bot plaintext cards may use media types; bypass peer edit window / attachment lock.
        val edited = runCatching {
            serviceMessageRepo.editOwn(messageId, bot.id, newBody, editedAt)
        }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("edit failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, existing.chatId, messageId, "editMessageCaption")
        fanoutBotEvent(
            userRepo = userRepo,
            participantRepository = conversationParticipantRepo,
            json = json,
            botId = bot.id,
            chatId = existing.chatId,
            event = com.maodouchat.server.messaging.v2.ServiceMessagingV2Event(
                action = "EDIT",
                targetMessageId = messageId,
                content = newBody,
                editedAt = editedAt,
            ),
            messagingV2Repository = messagingV2Repository,
        )
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", messageId)
put("caption", caption.take(200))
        }
    )
    }
}
