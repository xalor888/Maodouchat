package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Bot 聊天动作与置顶（sendChatAction / pinChatMessage / getChatPins）。 */
internal fun Route.configureBotChatActionRoutes(
    userRepo: UserRepository,
    pinnedMessageRepo: PinnedMessageRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
) {

    post("/api/bot/sendChatAction") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val action = obj["action"]?.jsonPrimitive?.content.orEmpty().lowercase().ifBlank { "typing" }
        if (chatId.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val isTyping = action in setOf("typing", "upload_photo", "record_video", "upload_video", "record_voice", "upload_document", "find_location")
        val payload = json.encodeToString(
            WsMessage(
                "USER_TYPING",
                json.encodeToString(TypingPayload(bot.id, chatId, isTyping))
            )
        )
        try {
            // 9.124 补：typing 侧信道同样过滤拉黑 bot 的接收方（与用户 WS TYPING 双向拉黑过滤一致）
            val typingPids = conversationParticipantRepo.participantIds(chatId).filter { it != bot.id }
            val botBlockedIds = try { userRepo.blockedEitherWayIdsInTx(bot.id, typingPids) } catch (_: Exception) { emptySet() }
            typingPids.forEach { pid ->
                if (pid in botBlockedIds) return@forEach
                LocalRealtimeBus.publish(pid, payload)
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendChatAction:$action")
        call.respond(
        buildJsonObject {
put("ok", true)
put("action", action)
        }
    )
    }


    post("/api/bot/pinChatMessage") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.repository.BotRepository.isBotDeliverable(bot.id)) {
            return@post call.respondBotUnavailable()
        }

        if (!com.maodouchat.server.service.RuntimeConfigService.isMessagePinEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("message_pin_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val messageId = obj["messageId"]?.jsonPrimitive?.content.orEmpty()
        if (chatId.isBlank() || messageId.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/messageId required"))
        }
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
put("pins", Json.parseToJsonElement(Json.encodeToString(outcome.pins)))
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

    get("/api/bot/getChatPins") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val chatId = call.request.queryParameters["chatId"].orEmpty()
        if (chatId.isBlank()) return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val pins = pinnedMessageRepo.list(chatId)
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "getChatPins")
        call.respond(
        buildJsonObject {
put("chatId", chatId)
put("pins", Json.parseToJsonElement(Json.encodeToString(pins)))
put("count", pins.size)
        }
    )
    }
}
