package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 投票操作与查询：votePoll / closePoll / getPoll。 */
internal fun Route.configureBotPollVoteRoutes(
    botSendRateLimiter: BoundedRateLimiter,
    conversationParticipantRepo: ConversationParticipantRepository,
) {
    post("/api/bot/votePoll") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.repository.BotRepository.isBotDeliverable(bot.id)) {
            return@post call.respondBotUnavailable()
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
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
        val obj = call.requireJsonObjectOr400(body) ?: return@post
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
