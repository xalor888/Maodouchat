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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bot 取消置顶消息。 */
internal fun Route.configureBotGeoMiscPinRoutes(
    userRepo: UserRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    pinnedMessageRepo: PinnedMessageRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
) {
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
