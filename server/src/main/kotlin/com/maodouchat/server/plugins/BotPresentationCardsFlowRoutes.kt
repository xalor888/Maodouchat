package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 富卡片·步骤对比：步骤/对比。 */
internal fun Route.configureBotPresentationCardsFlowRoutes(
    userRepository: UserRepository,
    participantRepository: ConversationParticipantRepository,
    serviceMessageRepository: ServiceMessageRepository,
    botRateLimiter: BoundedRateLimiter,
    json: Json,
) {

        post("/api/bot/sendSteps") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
            if (!com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled()) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("markdown_disabled"))
            }
            val body = call.receiveBoundedTextOrEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val fields = when (val parsed = parseBotSendStepsFields(obj)) {
                is BotSendStepsFieldsResult.Ok -> parsed.fields
                BotSendStepsFieldsResult.Invalid ->
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/steps required"))
            }
            val chatId = fields.chatId
            val title = fields.title
            val steps = fields.steps
            if (!participantRepository.isParticipant(chatId, bot.id)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
            }
            val lines = steps.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")
            val content = "### $title\n$lines"
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
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendSteps")
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("messageId", msgId)
    put("type", "MARKDOWN")
            }
        )
        }

        post("/api/bot/sendCompare") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
            if (!com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled()) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("markdown_disabled"))
            }
            val body = call.receiveBoundedTextOrEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val fields = when (val parsed = parseBotMetricCompareFields(
                obj,
                "left", "A", 80,
                "right", "B", 80,
                null, "", 0,
            )) {
                is BotMetricCompareFieldsResult.Ok -> parsed.fields
                BotMetricCompareFieldsResult.MissingRequired ->
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
            }
            val chatId = fields.chatId
            if (!participantRepository.isParticipant(chatId, bot.id)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
            }
            val content = buildBotCompareContent(fields.first, fields.second)
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
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendCompare")
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("messageId", msgId)
    put("type", "MARKDOWN")
            }
        )
        }
}
