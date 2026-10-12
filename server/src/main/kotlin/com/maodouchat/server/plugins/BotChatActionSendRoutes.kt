package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.WsMessage
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.PinnedMessageRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.blockedEitherWayIdsInTx
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bot 聊天动作发送（sendChatAction）。 */
internal fun Route.configureBotChatActionSendRoutes(
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
    val obj = call.requireJsonObjectOr400(body) ?: return@post
    val fields = when (val parsed = parseBotSendChatActionFields(obj)) {
        is BotSendChatActionFieldsResult.Ok -> parsed.fields
        BotSendChatActionFieldsResult.MissingRequired ->
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
    }
    val chatId = fields.chatId
    val action = fields.action
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
}
