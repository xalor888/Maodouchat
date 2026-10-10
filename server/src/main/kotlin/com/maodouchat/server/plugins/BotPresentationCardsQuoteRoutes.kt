package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 富卡片·引用横幅：引用卡片/横幅。 */
internal fun Route.configureBotPresentationCardsQuoteRoutes(
    userRepository: UserRepository,
    participantRepository: ConversationParticipantRepository,
    serviceMessageRepository: ServiceMessageRepository,
    botRateLimiter: BoundedRateLimiter,
    json: Json,
) {

        post("/api/bot/sendQuoteCard") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
            if (!com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled()) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("markdown disabled by admin"))
            }
            val body = call.receiveBoundedTextOrEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val fields = when (val parsed = parseBotQuoteCardFields(obj)) {
                is BotQuoteCardFieldsResult.Ok -> parsed.fields
                BotQuoteCardFieldsResult.MissingRequired ->
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/quote required"))
            }
            val chatId = fields.chatId
            if (!participantRepository.isParticipant(chatId, bot.id)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
            }
            val content = buildBotQuoteCardContent(fields.quote, fields.by)
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
                    type = "MARKDOWN",
                )
            }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendQuoteCard")
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("messageId", msgId)
    put("type", "MARKDOWN")
            }
        )
        }

        post("/api/bot/sendBanner") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
            if (!com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled()) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("markdown disabled by admin"))
            }
            val body = call.receiveBoundedTextOrEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val fields = when (val parsed = parseBotBannerFields(obj)) {
                is BotBannerFieldsResult.Ok -> parsed.fields
                BotBannerFieldsResult.MissingRequired ->
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/text required"))
            }
            val chatId = fields.chatId
            if (!participantRepository.isParticipant(chatId, bot.id)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
            }
            val content = buildBotBannerContent(fields.title, fields.text)
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
                    type = "MARKDOWN",
                )
            }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendBanner")
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("messageId", msgId)
    put("type", "MARKDOWN")
            }
        )
        }
}
