package com.maodouchat.server.plugins

import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 地理·杂项：资料名片/联系人卡片/取消置顶。 */
internal fun Route.configureBotGeoMiscRoutes(
    userRepo: UserRepository,
    pinnedMessageRepo: PinnedMessageRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    serviceMessageRepo: ServiceMessageRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {

        post("/api/bot/setMyName") {
            val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
            val body = call.receiveBoundedTextOrEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val name = when (val parsed = parseBotSetMyNameFields(obj)) {
                is BotSetMyNameFieldsResult.Ok -> parsed.fields.name
                BotSetMyNameFieldsResult.InvalidName ->
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

        post("/api/bot/sendContact") {
            val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
            if (!com.maodouchat.server.service.RuntimeConfigService.isContactCardEnabled()) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("contact_card_disabled"))
            }
            val body = call.receiveBoundedTextOrEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
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

        post("/api/bot/unpinChatMessage") {
            val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
            if (!com.maodouchat.server.repository.BotRepository.isBotDeliverable(bot.id)) {
                return@post call.respondBotUnavailable()
            }
            val body = call.receiveBoundedTextOrEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
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
    putJsonElement("pins", outcome.pins)
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
