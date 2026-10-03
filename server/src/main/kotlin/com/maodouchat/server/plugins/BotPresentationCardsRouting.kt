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

/** Bot 卡片/提示展示（自 BotPresentationRouting.kt 拆分，B12）。 */
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
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
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
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
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
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
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


    get("/api/bot/getVersion") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "getVersion")
        call.respond(
        buildJsonObject {
put("ok", true)
put("botId", bot.id)
put("api", "maodouchat-bot")
put("surface", 34)
put("serverTime", System.currentTimeMillis())
        }
    )
    }


    post("/api/bot/sendNotice") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
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
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
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


    get("/api/bot/healthz") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "healthz")
        call.respond(
        buildJsonObject {
put("ok", true)
put("botId", bot.id)
put("status", "up")
put("serverTime", System.currentTimeMillis())
        }
    )
    }


    post("/api/bot/sendBanner") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("markdown disabled by admin"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
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
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
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


    get("/api/bot/uptime") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "uptime")
        call.respond(
        buildJsonObject {
put("ok", true)
put("botId", bot.id)
put("serverTime", System.currentTimeMillis())
put("surface", 39)
        }
    )
    }


    post("/api/bot/sendTimeline") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("markdown_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
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
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
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
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
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
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
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


    get("/api/bot/echoTime") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "echoTime")
        call.respond(
        buildJsonObject {
put("ok", true)
put("botId", bot.id)
put("serverTime", System.currentTimeMillis())
put("surface", 39)
        }
    )
    }


    post("/api/bot/sendMentionCard") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("markdown_disabled"))
        }
        if (!com.maodouchat.server.service.RuntimeConfigService.isMentionsEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("mentions_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val fields = when (val parsed = parseBotMentionNudgeFields(obj, "mention")) {
            is BotMentionNudgeFieldsResult.Ok -> parsed.fields
            BotMentionNudgeFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        }
        val chatId = fields.chatId
        val label = fields.label
        if (!participantRepository.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val content = buildBotMentionNudgeContent("> @", label, "")
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
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendMentionCard")
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "MARKDOWN")
        }
    )
    }

    post("/api/bot/sendInviteHint") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isGroupInvitesEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("group_invites_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
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


    get("/api/bot/versionz") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "versionz")
        call.respond(
        buildJsonObject {
put("ok", true)
put("botId", bot.id)
put("surface", 60)
put("serverTime", System.currentTimeMillis())
        }
    )
    }


    post("/api/bot/sendNudgeCard") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("markdown_disabled"))
        }
        if (!com.maodouchat.server.service.RuntimeConfigService.isNudgeEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("nudge_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val fields = when (val parsed = parseBotMentionNudgeFields(obj, "nudge")) {
            is BotMentionNudgeFieldsResult.Ok -> parsed.fields
            BotMentionNudgeFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        }
        val chatId = fields.chatId
        val label = fields.label
        if (!participantRepository.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val content = buildBotMentionNudgeContent("> ~nudge:", label, "~")
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
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendNudgeCard")
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "MARKDOWN")
        }
    )
    }

    post("/api/bot/sendSafetyHint") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isSafetyCodeEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("safety_code_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
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

    get("/api/bot/getTrustFlags") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "getTrustFlags")
        call.respond(
        buildJsonObject {
put("ok", true)
put("nudgeEnabled", com.maodouchat.server.service.RuntimeConfigService.isNudgeEnabled())
put("safetyCodeEnabled", com.maodouchat.server.service.RuntimeConfigService.isSafetyCodeEnabled())
put("qrCodeEnabled", com.maodouchat.server.service.RuntimeConfigService.isQrCodeEnabled())
put("contactCardEnabled", com.maodouchat.server.service.RuntimeConfigService.isContactCardEnabled())
put("spoilerMediaEnabled", com.maodouchat.server.service.RuntimeConfigService.isSpoilerMediaEnabled())
put("autoDownloadEnabled", com.maodouchat.server.service.RuntimeConfigService.isAutoDownloadEnabled())
put("staticLocationEnabled", com.maodouchat.server.service.RuntimeConfigService.isStaticLocationEnabled())
put("fileShareEnabled", com.maodouchat.server.service.RuntimeConfigService.isFileShareEnabled())
put("secretChatEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretChatEnabled())
put("screenSecureRuntimeEnabled", com.maodouchat.server.service.RuntimeConfigService.isScreenSecureRuntimeEnabled())
put("imageSendEnabled", com.maodouchat.server.service.RuntimeConfigService.isImageSendEnabled())
put("videoSendEnabled", com.maodouchat.server.service.RuntimeConfigService.isVideoSendEnabled())
put("gifSendEnabled", com.maodouchat.server.service.RuntimeConfigService.isGifSendEnabled())
put("blindWatermarkEnabled", com.maodouchat.server.service.RuntimeConfigService.isBlindWatermarkEnabled())
put("voiceCallEnabled", com.maodouchat.server.service.RuntimeConfigService.isVoiceCallEnabled())
put("videoCallEnabled", com.maodouchat.server.service.RuntimeConfigService.isVideoCallEnabled())
put("chatWallpaperEnabled", com.maodouchat.server.service.RuntimeConfigService.isChatWallpaperEnabled())
put("chatFontScaleEnabled", com.maodouchat.server.service.RuntimeConfigService.isChatFontScaleEnabled())
put("unreadPriorityEnabled", com.maodouchat.server.service.RuntimeConfigService.isUnreadPriorityEnabled())
put("ringtoneEnabled", com.maodouchat.server.service.RuntimeConfigService.isRingtoneEnabled())
put("notificationSoundEnabled", com.maodouchat.server.service.RuntimeConfigService.isNotificationSoundEnabled())
put("notificationPreviewEnabled", com.maodouchat.server.service.RuntimeConfigService.isNotificationPreviewEnabled())
put("pushNotificationsEnabled", com.maodouchat.server.service.RuntimeConfigService.isPushNotificationsEnabled())
put("taskRemindersEnabled", com.maodouchat.server.service.RuntimeConfigService.isTaskRemindersEnabled())
put("dndEnabled", com.maodouchat.server.service.RuntimeConfigService.isDndEnabled())
put("inAppSoundsEnabled", com.maodouchat.server.service.RuntimeConfigService.isInAppSoundsEnabled())
put("hapticsEnabled", com.maodouchat.server.service.RuntimeConfigService.isHapticsEnabled())
put("chatAnimationsEnabled", com.maodouchat.server.service.RuntimeConfigService.isChatAnimationsEnabled())
put("navTransitionsEnabled", com.maodouchat.server.service.RuntimeConfigService.isNavTransitionsEnabled())
put("screenshotDetectEnabled", com.maodouchat.server.service.RuntimeConfigService.isScreenshotDetectEnabled())
put("recentsExclusionEnabled", com.maodouchat.server.service.RuntimeConfigService.isRecentsExclusionEnabled())
put("secretCopyBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretCopyBlockEnabled())
put("secretMediaExportBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretMediaExportBlockEnabled())
put("secretForwardBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretForwardBlockEnabled())
put("secretChatExportBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretChatExportBlockEnabled())
put("secretAutoDisappearEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretAutoDisappearEnabled())
put("secretLinkPreviewBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretLinkPreviewBlockEnabled())
put("secretExternalLinkBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretExternalLinkBlockEnabled())
put("secretNotifPreviewBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretNotifPreviewBlockEnabled())
put("secretListPreviewBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretListPreviewBlockEnabled())
put("secretReactionBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretReactionBlockEnabled())
put("secretStarBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretStarBlockEnabled())
put("captureAlertEnabled", com.maodouchat.server.service.RuntimeConfigService.isCaptureAlertEnabled())
put("sealedSenderEnabled", com.maodouchat.server.service.RuntimeConfigService.isSealedSenderEnabled())
put("forceE2eeBanner", com.maodouchat.server.service.RuntimeConfigService.get(com.maodouchat.server.service.RuntimeConfigService.KEY_FORCE_E2EE_BANNER))
put("serverTime", System.currentTimeMillis())
        }
    )
    }



    post("/api/bot/sendQrHint") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isQrCodeEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("qr_code_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
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

    post("/api/bot/sendContactCard") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("markdown_disabled"))
        }
        if (!com.maodouchat.server.service.RuntimeConfigService.isContactCardEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("contact_card_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val fields = when (val result = parseBotSendContactCardFields(obj)) {
            is BotSendContactCardFieldsResult.Ok -> result.fields
            BotSendContactCardFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        }
        val chatId = fields.chatId
        if (!participantRepository.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val content = buildBotContactCardContent(fields.name)
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
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendContactCard")
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "MARKDOWN")
        }
    )
    }




    post("/api/bot/sendSpoilerHint") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isSpoilerMediaEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("spoiler_media_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
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

    post("/api/bot/sendDownloadHint") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isAutoDownloadEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("auto_download_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
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


    get("/api/bot/statusz") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "statusz")
        call.respond(
        buildJsonObject {
put("ok", true)
put("status", "up")
put("botId", bot.id)
put("surface", 60)
put("serverTime", System.currentTimeMillis())
        }
    )
    }


    post("/api/bot/sendLocationHint") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isStaticLocationEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("static_location_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
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
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
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

    post("/api/bot/sendSecretHint") {
        call.respond(HttpStatusCode.Gone, ErrorResponse("sendSecretHint_removed"))
    }

    post("/api/bot/sendSecureHint") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isScreenSecureRuntimeEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("screen_secure_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
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





    post("/api/bot/sendPhotoHint") {
        val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isImageSendEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("image_send_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
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
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
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
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
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
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
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
