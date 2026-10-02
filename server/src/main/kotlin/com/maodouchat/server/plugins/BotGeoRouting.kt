package com.maodouchat.server.plugins

import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.BlobStore
import com.maodouchat.server.service.GroupMembershipService
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 位置/联系人/位置类消息与取消置顶（自 BotCoreRouting.kt 拆分，B12）。 */
internal fun Route.configureBotGeoRoutes(
    userRepo: UserRepository,
    pinnedMessageRepo: PinnedMessageRepository,
    serviceMessageRepo: ServiceMessageRepository,
    groupMembershipService: GroupMembershipService,
    groupModerationRepo: GroupModerationRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {

    post("/api/bot/sendLocation") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isStaticLocationEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("static_location_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId: String
        val lat: Double
        val lon: Double
        val title: String
        when (val r = parseBotSendLocationFields(obj)) {
            is BotSendLocationFieldsResult.Ok -> {
                chatId = r.fields.chatId
                lat = r.fields.latitude
                lon = r.fields.longitude
                title = r.fields.title
            }
            BotSendLocationFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/latitude/longitude required"))
            BotSendLocationFieldsResult.InvalidCoordinates ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid coordinates"))
        }
        // 校验顺序与原处理器一致（必填 → 坐标范围在纯函数里 → 成员检查在处理器里）
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        // Bot plaintext location marker（组装逻辑收敛至 buildBotLocationContent，逐字一致）。
        val content = buildBotLocationContent(lat, lon, title)
        val ok = runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "LOCATION")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendLocation")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = content,
            type = "LOCATION", timestamp = now, status = "SENT"
        )
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage, messagingV2Repository = messagingV2Repository)
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("latitude", lat)
put("longitude", lon)
        }
    )
    }

    get("/api/bot/listChatPolls") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val chatId = call.request.queryParameters["chatId"].orEmpty()
        if (chatId.isBlank()) return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val polls = com.maodouchat.server.repository.PollRepository.listChatPolls(chatId, bot.id)
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "listChatPolls")
        call.respond(
        buildJsonObject {
put("chatId", chatId)
put("polls", Json.parseToJsonElement(Json.encodeToString(polls)))
put("count", polls.size)
        }
    )
    }


    post("/api/bot/setMyName") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val name = (obj["name"] ?: obj["displayName"])?.jsonPrimitive?.content.orEmpty().trim().take(120)
        if (name.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid name"))
        }
        val updated = com.maodouchat.server.repository.BotRepository.setMyName(bot.id, name)
            ?: return@post call.respondBotUnavailable()
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "setMyName")
        call.respond(
        buildJsonObject {
put("ok", true)
put("botId", updated.id)
put("name", updated.name)
        }
    )
    }

    get("/api/bot/getChatHistory") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val chatId = call.request.queryParameters["chatId"].orEmpty()
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 100)
        if (chatId.isBlank()) return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        // Bot history exposes only server-authored service messages; peer E2EE bodies stay device-local.
        val history = serviceMessageRepo.list(chatId, limit, bot.id)
        // 8.46：混合类型 List<Map<String,Any>> encodeToString 运行时抛 SerializationException → buildJsonArray
        val historyArray = buildJsonArray {
            history.forEach { m ->
                add(buildJsonObject {
                    put("messageId", m.id)
                    put("chatId", m.chatId)
                    put("senderId", m.senderId)
                    put("type", m.type)
                    put("timestamp", m.timestamp)
                    put("status", m.status)
                    put("content", m.content.take(4000))
                    put("sealedSender", m.sealedSender)
                })
            }
        }
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "getChatHistory")
        call.respond(
        buildJsonObject {
put("chatId", chatId)
put("messages", historyArray)
put("count", history.size)
        }
    )
    }

    post("/api/bot/sendContact") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isContactCardEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("contact_card_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val fields = when (val parsed = parseBotSendContactFields(obj)) {
            is BotSendContactFieldsResult.Ok -> parsed.fields
            BotSendContactFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId and contact fields required"))
        }
        val chatId = fields.chatId
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val content = buildBotContactContent(fields.contactName, fields.phone, fields.userId)
        val ok = runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "TEXT")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendContact")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = content,
            type = "TEXT", timestamp = now, status = "SENT"
        )
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage, messagingV2Repository = messagingV2Repository)
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
        }
    )
    }

    post("/api/bot/sendVenue") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val fields = when (val parsed = parseBotSendVenueFields(obj)) {
            is BotSendVenueFieldsResult.Ok -> parsed.fields
            BotSendVenueFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/latitude/longitude/title required"))
            BotSendVenueFieldsResult.InvalidCoordinates ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid coordinates"))
        }
        val chatId = fields.chatId
        val lat = fields.latitude
        val lon = fields.longitude
        val title = fields.title
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val content = buildBotVenueContent(lat, lon, title, fields.address)
        val ok = runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "LOCATION")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendVenue")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = content,
            type = "LOCATION", timestamp = now, status = "SENT"
        )
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage, messagingV2Repository = messagingV2Repository)
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("latitude", lat)
put("longitude", lon)
put("title", title)
        }
    )
    }



    post("/api/bot/unpinChatMessage") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.repository.BotRepository.isBotDeliverable(bot.id)) {
            return@post call.respondBotUnavailable()
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val fields = when (val parsed = parseBotUnpinChatMessageFields(obj)) {
            is BotUnpinChatMessageFieldsResult.Ok -> parsed.fields
            BotUnpinChatMessageFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/messageId required"))
        }
        val chatId = fields.chatId
        val messageId = fields.messageId
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val chat = conversationQueryRepo.getById(chatId)
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("chat not found"))
        val actorIsManager = if (chat.isGroup) conversationParticipantRepo.isOwnerOrAdmin(chatId, bot.id) else true
        // toggle: if currently pinned -> unpins; if not pinned, pin then toggle again would pin — force unpin via toggle only when pinned
        val before = pinnedMessageRepo.list(chatId).any { it.messageId == messageId }
        if (!before) {
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "unpinChatMessage")
            return@post call.respond(
        buildJsonObject {
put("ok", true)
put("pinned", false)
put("messageId", messageId)
put("alreadyUnpinned", true)
        }
    )
        }
        val outcome = pinnedMessageRepo.toggle(
            chatId = chatId,
            messageId = messageId,
            actorId = bot.id,
            actorIsManager = actorIsManager,
            requireBotDeliverable = true
        )
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "unpinChatMessage")
        when (outcome.result) {
            com.maodouchat.server.repository.PinnedMessageRepository.PinResult.UNPINNED,
            com.maodouchat.server.repository.PinnedMessageRepository.PinResult.PINNED -> {
                val payload = PinnedMessagesUpdatedPayload(chatId, bot.id, outcome.pins)
                val pinJson = json.encodeToString(
                    WsMessage.serializer(),
                    WsMessage(
                        "PINNED_MESSAGES_UPDATED",
                        json.encodeToString(PinnedMessagesUpdatedPayload.serializer(), payload)
                    )
                )
                val fanoutPids = conversationParticipantRepo.participantIds(chatId)
        val botBlockedIds = try { userRepo.blockedEitherWayIdsInTx(bot.id, fanoutPids) } catch (_: Exception) { emptySet() }
        fanoutPids.forEach { pid ->
            if (pid in botBlockedIds) return@forEach
            LocalRealtimeBus.publish(pid, pinJson)
        }
                call.respond(
        buildJsonObject {
put("ok", true)
put("pinned", false)
put("pins", Json.parseToJsonElement(Json.encodeToString(outcome.pins)))
put("count", outcome.pins.size)
        }
    )
            }
            com.maodouchat.server.repository.PinnedMessageRepository.PinResult.NOT_FOUND ->
                call.respond(HttpStatusCode.NotFound, ErrorResponse("message not found"))
            com.maodouchat.server.repository.PinnedMessageRepository.PinResult.FORBIDDEN ->
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
            else -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("unpin failed: ${outcome.result}"))
        }
    }
}
