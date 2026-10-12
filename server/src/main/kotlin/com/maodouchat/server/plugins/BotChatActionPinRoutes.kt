package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.WsMessage
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.PinnedMessageRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.blockedEitherWayIdsInTx
import com.maodouchat.server.repository.isBotDeliverable
import com.maodouchat.server.repository.logCommand
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bot 消息置顶（pinChatMessage）。 */
internal fun Route.configureBotChatActionPinRoutes(
    userRepo: UserRepository,
    pinnedMessageRepo: PinnedMessageRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
) {
post("/api/bot/pinChatMessage") {
    val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
    if (!com.maodouchat.server.repository.BotRepository.isBotDeliverable(bot.id)) {
        return@post call.respondBotUnavailable()
    }

    if (!com.maodouchat.server.service.RuntimeConfigService.isMessagePinEnabled()) {
        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("message_pin_disabled"))
    }
    val body = call.receiveBoundedTextOrEmpty()
    val obj = call.requireJsonObjectOr400(body) ?: return@post
    val fields = when (val parsed = parseBotPinChatMessageFields(obj)) {
        is BotPinChatMessageFieldsResult.Ok -> parsed.fields
        BotPinChatMessageFieldsResult.MissingRequired ->
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/messageId required"))
    }
    val chatId = fields.chatId
    val messageId = fields.messageId
    if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
    }
    val chat = conversationQueryRepo.getById(chatId)
        ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("chat not found"))
    // Bots act as managers for pin in groups where they are members; 1:1 always ok.
    // 修复：此前恒 true 使普通成员 bot 也可钉任意消息；与 unpinChatMessage 的
    // isOwnerOrAdmin 口径对齐（当前 bot 入群即 ADMIN，防御未来成员角色变化）。
    val actorIsManager = if (chat.isGroup) {
        conversationParticipantRepo.isOwnerOrAdmin(chatId, bot.id)
    } else {
        true
    }
    val outcome = pinnedMessageRepo.toggle(
        chatId = chatId,
        messageId = messageId,
        actorId = bot.id,
        actorIsManager = actorIsManager,
        requireBotDeliverable = true
    )
    com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "pinChatMessage")
    when (outcome.result) {
        com.maodouchat.server.repository.PinnedMessageRepository.PinResult.PINNED,
        com.maodouchat.server.repository.PinnedMessageRepository.PinResult.UNPINNED -> {
            val pinned = outcome.result == com.maodouchat.server.repository.PinnedMessageRepository.PinResult.PINNED
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
put("pinned", pinned)
putJsonElement("pins", outcome.pins)
put("count", outcome.pins.size)
    }
)
        }
        com.maodouchat.server.repository.PinnedMessageRepository.PinResult.NOT_FOUND ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse("message not found"))
        com.maodouchat.server.repository.PinnedMessageRepository.PinResult.FORBIDDEN ->
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        com.maodouchat.server.repository.PinnedMessageRepository.PinResult.LIMIT ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("pin limit reached"))
        com.maodouchat.server.repository.PinnedMessageRepository.PinResult.NOT_PINNABLE ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("message not pinnable"))
    }
}
}
