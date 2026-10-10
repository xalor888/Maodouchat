package com.maodouchat.server.plugins

import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.PinnedMessageRepository
import com.maodouchat.server.repository.UserRepository
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonElement
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.WsMessage

/** Bot 取消群置顶消息。 */
internal fun Route.configureBotChatPinRoutes(
    userRepo: UserRepository,
    pinnedMessageRepo: PinnedMessageRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    post("/api/bot/unpinAllChatMessages") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.repository.BotRepository.isBotDeliverable(bot.id)) {
            return@post call.respondBotUnavailable()
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val fields = when (val parsed = parseBotUnpinAllChatMessagesFields(obj)) {
            is BotUnpinAllChatMessagesFieldsResult.Ok -> parsed.fields
            BotUnpinAllChatMessagesFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        }
        val chatId = fields.chatId
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val chat = conversationQueryRepo.getById(chatId)
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("chat not found"))
        val actorIsManager = if (chat.isGroup) conversationParticipantRepo.isOwnerOrAdmin(chatId, bot.id) else true
        val outcome = pinnedMessageRepo.clearAll(
            chatId = chatId,
            actorId = bot.id,
            actorIsManager = actorIsManager,
            requireBotDeliverable = true
        )
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "unpinAllChatMessages")
        if (outcome.result == com.maodouchat.server.repository.PinnedMessageRepository.PinResult.FORBIDDEN) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        }
        if (outcome.result == com.maodouchat.server.repository.PinnedMessageRepository.PinResult.NOT_FOUND) {
            return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("chat not found"))
        }
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
put("chatId", chatId)
putJsonElement("pins", outcome.pins)
put("count", 0)
        }
    )
    }
}
