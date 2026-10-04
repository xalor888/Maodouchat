package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Bot 群投票与骰子（sendPoll / sendDice / votePoll / closePoll / getPoll）。 */
internal fun Route.configureBotPollRoutes(
    userRepo: UserRepository,
    serviceMessageRepo: ServiceMessageRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {

    post("/api/bot/sendPoll") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.repository.BotRepository.isBotDeliverable(bot.id)) {
            return@post call.respondBotUnavailable()
        }
        if (!com.maodouchat.server.service.RuntimeConfigService.isPollsEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("polls_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val fields = when (val parsed = parseBotSendPollFields(obj)) {
            is BotSendPollFieldsResult.Ok -> parsed.fields
            BotSendPollFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/question/options(>=2) required"))
        }
        val chatId = fields.chatId
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        if (!com.maodouchat.server.service.RuntimeConfigService.isGroupPlayEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("group play disabled"))
        }
        val poll = com.maodouchat.server.repository.PollRepository.createPoll(
            chatId = chatId,
            creatorId = bot.id,
            question = fields.question,
            options = fields.options,
            multi = fields.multi,
            anonymous = fields.anonymous,
            closesAt = fields.closesAt,
            requireBotDeliverable = true
        ) ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("poll create failed"))
        // Also drop a bot plaintext summary message so chat history shows the poll.
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val summary = buildBotPollSummary(poll.question, poll.options, poll.id)
        runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, summary, now, "TEXT")
        }
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = summary,
            type = "TEXT", timestamp = now, status = "SENT"
        )
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage, messagingV2Repository = messagingV2Repository)
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, poll.id, "sendPoll")
        call.respond(
        buildJsonObject {
put("ok", true)
putJsonElement("poll", poll)
put("messageId", msgId)
        }
    )
    }


    post("/api/bot/sendDice") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val fields = when (val parsed = parseBotSendDiceFields(obj)) {
            is BotSendDiceFieldsResult.Ok -> parsed.fields
            BotSendDiceFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        }
        val chatId = fields.chatId
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        if (!com.maodouchat.server.service.RuntimeConfigService.isGroupPlayEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("group play disabled"))
        }
        val value = (1..fields.sides).random()
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val content = buildBotDiceContent(fields.diceEmoji, value, fields.sides)
        val ok = runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "TEXT")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendDice")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = content,
            type = "TEXT", timestamp = now, status = "SENT"
        )
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage, messagingV2Repository = messagingV2Repository)
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("value", value)
put("sides", fields.sides)
        }
    )
    }

    post("/api/bot/votePoll") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.repository.BotRepository.isBotDeliverable(bot.id)) {
            return@post call.respondBotUnavailable()
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val fields = when (val parsed = parseBotVotePollFields(obj)) {
            is BotVotePollFieldsResult.Ok -> parsed.fields
            BotVotePollFieldsResult.InvalidOptionIndexes ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid optionIndexes"))
            BotVotePollFieldsResult.Required ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("pollId/optionIndexes required"))
        }
        val pollId = fields.pollId
        val indexes = fields.optionIndexes
        val poll = com.maodouchat.server.repository.PollRepository.vote(
            pollId = pollId,
            userId = bot.id,
            optionIndexes = indexes,
            requireBotDeliverable = true
        )
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("vote failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, poll.chatId, pollId, "votePoll")
        call.respond(
        buildJsonObject {
put("ok", true)
putJsonElement("poll", poll)
        }
    )
    }

    post("/api/bot/closePoll") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.repository.BotRepository.isBotDeliverable(bot.id)) {
            return@post call.respondBotUnavailable()
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val pollId = when (val parsed = parseBotClosePollFields(obj)) {
            is BotClosePollFieldsResult.Ok -> parsed.fields.pollId
            BotClosePollFieldsResult.Invalid ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("pollId required"))
        }
        val poll = com.maodouchat.server.repository.PollRepository.closePoll(
            pollId = pollId,
            userId = bot.id,
            requireBotDeliverable = true
        )
            ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("close failed (creator only)"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, poll.chatId, pollId, "closePoll")
        call.respond(
        buildJsonObject {
put("ok", true)
putJsonElement("poll", poll)
        }
    )
    }

    get("/api/bot/getPoll") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val pollId = call.requireNonBlankParamOr400("pollId", "pollId required") ?: return@get
        val poll = com.maodouchat.server.repository.PollRepository.getPoll(pollId, bot.id)
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("poll not found"))
        if (!conversationParticipantRepo.isParticipant(poll.chatId, bot.id)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, poll.chatId, pollId, "getPoll")
        call.respond(
        buildJsonObject {
put("ok", true)
putJsonElement("poll", poll)
        }
    )
    }
}
