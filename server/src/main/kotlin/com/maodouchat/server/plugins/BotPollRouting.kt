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
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val question = obj["question"]?.jsonPrimitive?.content.orEmpty()
        val optionsEl = obj["options"]
        val options = when (optionsEl) {
            is kotlinx.serialization.json.JsonArray -> optionsEl.mapNotNull {
                runCatching { it.jsonPrimitive.content }.getOrNull()?.trim()?.takeIf { s -> s.isNotBlank() }
            }
            else -> emptyList()
        }
        val multi = obj["multi"]?.jsonPrimitive?.booleanOrNull
            ?: obj["allowsMultipleAnswers"]?.jsonPrimitive?.booleanOrNull
            ?: false
        val anonymous = obj["anonymous"]?.jsonPrimitive?.booleanOrNull
            ?: obj["isAnonymous"]?.jsonPrimitive?.booleanOrNull
            ?: true
        val closesAt = obj["closesAt"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: obj["closeDate"]?.jsonPrimitive?.content?.toLongOrNull()
        if (chatId.isBlank() || question.isBlank() || options.size < 2) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/question/options(>=2) required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        if (!com.maodouchat.server.service.RuntimeConfigService.isGroupPlayEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("group play disabled"))
        }
        val poll = com.maodouchat.server.repository.PollRepository.createPoll(
            chatId = chatId,
            creatorId = bot.id,
            question = question,
            options = options,
            multi = multi,
            anonymous = anonymous,
            closesAt = closesAt,
            requireBotDeliverable = true
        ) ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("poll create failed"))
        // Also drop a bot plaintext summary message so chat history shows the poll.
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val summary = buildString {
            append("📊 ")
            append(poll.question)
            poll.options.forEachIndexed { i, o ->
                append("\n")
                append(i + 1)
                append(". ")
                append(o)
            }
            append("\n[poll:")
            append(poll.id)
            append("]")
        }
        runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, summary, now, "TEXT")
        }
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = summary,
            type = "TEXT", timestamp = now, status = "SENT"
        )
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage)
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, poll.id, "sendPoll")
        call.respond(
        buildJsonObject {
put("ok", true)
put("poll", Json.parseToJsonElement(Json.encodeToString(poll)))
put("messageId", msgId)
        }
    )
    }


    post("/api/bot/sendDice") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        // emoji 语义映射（对齐 Telegram 骰子）：🎲🎯🎳 6 面、🏀⚽ 5 面、🎰 64 面；显式 sides 优先
        val diceEmoji = obj["emoji"]?.jsonPrimitive?.content.orEmpty().takeIf { it.isNotBlank() }
        val sides = (obj["sides"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: when (diceEmoji) {
                "🏀", "⚽" -> 5
                "🎰" -> 64
                else -> 6
            }).coerceIn(2, 100)
        if (chatId.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        if (!com.maodouchat.server.service.RuntimeConfigService.isGroupPlayEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("group play disabled"))
        }
        val value = (1..sides).random()
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val content = "${diceEmoji ?: "🎲"} ${value}/${sides}"
        val ok = runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "TEXT")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendDice")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = content,
            type = "TEXT", timestamp = now, status = "SENT"
        )
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage)
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("value", value)
put("sides", sides)
        }
    )
    }

    post("/api/bot/votePoll") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.repository.BotRepository.isBotDeliverable(bot.id)) {
            return@post call.respondBotUnavailable()
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val pollId = obj["pollId"]?.jsonPrimitive?.content.orEmpty()
        // 9.157：同用户投票端点——非法元素整体拒绝，不静默截成子集投票
        val indexes = buildList {
            val arr = obj["optionIndexes"] as? kotlinx.serialization.json.JsonArray
            if (arr != null) {
                for (element in arr) {
                    val v = (element as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                    if (v == null || v < 0) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid optionIndexes"))
                    }
                    add(v)
                }
            } else {
                val single = (obj["optionIndex"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                if (single == null || single < 0) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("pollId/optionIndexes required"))
                }
                add(single)
            }
        }
        if (pollId.isBlank() || indexes.isEmpty()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("pollId/optionIndexes required"))
        }
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
put("poll", Json.parseToJsonElement(Json.encodeToString(poll)))
        }
    )
    }

    post("/api/bot/closePoll") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.repository.BotRepository.isBotDeliverable(bot.id)) {
            return@post call.respondBotUnavailable()
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val pollId = obj["pollId"]?.jsonPrimitive?.content.orEmpty()
        if (pollId.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("pollId required"))
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
put("poll", Json.parseToJsonElement(Json.encodeToString(poll)))
        }
    )
    }

    get("/api/bot/getPoll") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val pollId = call.request.queryParameters["pollId"].orEmpty()
        if (pollId.isBlank()) return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("pollId required"))
        val poll = com.maodouchat.server.repository.PollRepository.getPoll(pollId, bot.id)
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("poll not found"))
        if (!conversationParticipantRepo.isParticipant(poll.chatId, bot.id)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, poll.chatId, pollId, "getPoll")
        call.respond(
        buildJsonObject {
put("ok", true)
put("poll", Json.parseToJsonElement(Json.encodeToString(poll)))
        }
    )
    }
}
