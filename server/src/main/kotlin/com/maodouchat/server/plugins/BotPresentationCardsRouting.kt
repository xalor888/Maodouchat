package com.maodouchat.server.plugins

import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
/** Bot 卡片展示（自 BotPresentationRouting.kt 拆分，B12；提示与探针已另立文件）。 */
internal fun Route.configureBotPresentationCardsRoutes(
    userRepository: UserRepository,
    participantRepository: ConversationParticipantRepository,
    serviceMessageRepository: ServiceMessageRepository,
    botRateLimiter: BoundedRateLimiter,
    json: Json,
) {

        post("/api/bot/echo") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
            val body = call.receiveBoundedTextOrEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val text = parseBotEchoFields(obj).text
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "echo")
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("botId", bot.id)
    put("echo", text)
    put("serverTime", System.currentTimeMillis())
            }
        )
        }

        post("/api/bot/sendToast") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
            val body = call.receiveBoundedTextOrEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val fields = when (val parsed = parseBotSendToastFields(obj)) {
                is BotSendToastFieldsResult.Ok -> parsed.fields
                BotSendToastFieldsResult.MissingRequired ->
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/text required"))
            }
            val chatId = fields.chatId
            val text = fields.text
            if (!participantRepository.isParticipant(chatId, bot.id)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
            }
            val content = buildBotToastContent(text)
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
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendToast")
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("messageId", msgId)
    put("type", "SYSTEM")
            }
        )
        }

        post("/api/bot/sendKeyValue") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
            if (!com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled()) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("markdown disabled by admin"))
            }
            val body = call.receiveBoundedTextOrEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val fields = when (val parsed = parseBotKeyValueFields(obj)) {
                is BotKeyValueFieldsResult.Ok -> parsed.fields
                BotKeyValueFieldsResult.MissingRequired ->
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
            }
            val chatId = fields.chatId
            if (!participantRepository.isParticipant(chatId, bot.id)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
            }
            val content = buildBotKeyValueContent(fields.key, fields.value)
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
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendKeyValue")
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("messageId", msgId)
    put("type", "MARKDOWN")
            }
        )
        }

        post("/api/bot/sendNotice") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
            val body = call.receiveBoundedTextOrEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val fields = when (val parsed = parseBotSendNoticeFields(obj)) {
                is BotSendNoticeFieldsResult.Ok -> parsed.fields
                BotSendNoticeFieldsResult.MissingRequired ->
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/text required"))
            }
            val chatId = fields.chatId
            val text = fields.text
            if (!participantRepository.isParticipant(chatId, bot.id)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
            }
            val content = buildBotNoticeContent(text)
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
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendNotice")
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("messageId", msgId)
    put("type", "SYSTEM")
            }
        )
        }

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

        post("/api/bot/sendJsonCard") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
            if (!com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled()) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("markdown disabled by admin"))
            }
            val body = call.receiveBoundedTextOrEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val fields = when (val parsed = parseBotJsonCardFields(obj)) {
                is BotJsonCardFieldsResult.Ok -> parsed.fields
                BotJsonCardFieldsResult.MissingRequired ->
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/json required"))
            }
            val chatId = fields.chatId
            if (!participantRepository.isParticipant(chatId, bot.id)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
            }
            val content = buildBotJsonCardContent(fields.payload)
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
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendJsonCard")
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("messageId", msgId)
    put("type", "MARKDOWN")
            }
        )
        }

        post("/api/bot/sendTimeline") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
            if (!com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled()) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("markdown_disabled"))
            }
            val body = call.receiveBoundedTextOrEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val fields = when (val parsed = parseBotSendTimelineFields(obj)) {
                is BotSendTimelineFieldsResult.Ok -> parsed.fields
                BotSendTimelineFieldsResult.MissingRequired ->
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/items required"))
            }
            val chatId = fields.chatId
            if (!participantRepository.isParticipant(chatId, bot.id)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
            }
            val content = buildBotTimelineContent(fields.title, fields.items)
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
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendTimeline")
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("messageId", msgId)
    put("type", "MARKDOWN")
            }
        )
        }

        post("/api/bot/sendMetric") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
            if (!com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled()) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("markdown_disabled"))
            }
            val body = call.receiveBoundedTextOrEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val fields = when (val parsed = parseBotMetricCompareFields(
                obj,
                "label", "metric", 40,
                "value", "0", 40,
                "unit", "", 20,
            )) {
                is BotMetricCompareFieldsResult.Ok -> parsed.fields
                BotMetricCompareFieldsResult.MissingRequired ->
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
            }
            val chatId = fields.chatId
            if (!participantRepository.isParticipant(chatId, bot.id)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
            }
            val content = buildBotMetricContent(fields.first, fields.second, fields.third)
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
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendMetric")
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("messageId", msgId)
    put("type", "MARKDOWN")
            }
        )
        }

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
