package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.enqueueCallbackIfAuthorized
import com.maodouchat.server.repository.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 回调按钮：inline 按钮回调查询的接收与投递。 */
internal fun Route.configureBotInteractionCallbackRoutes(
    userRepo: UserRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
) {

    post("/api/chats/{chatId}/bot-callback") {
        if (call.rejectIfMaintenance()) return@post
        val userId = call.requireUserId()
        // 8.33 修复：封禁用户不得触发 bot 回调（bot 平台交互面一致收口）
        if (call.rejectIfSuspended(userRepo, userId)) return@post
        val chatId = call.requirePathParamOr400("chatId", "缺少聊天 ID") ?: return@post
        if (!conversationParticipantRepo.isParticipant(chatId, userId)) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该聊天"))
            return@post
        }
        val body = call.receiveBoundedTextOrEmpty(16_384)
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val fields = when (val parsed = parseBotChatCallbackFields(obj)) {
            is BotChatCallbackFieldsResult.Ok -> parsed.fields
            BotChatCallbackFieldsResult.Invalid ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("messageId/botUserId/callbackData required"))
        }
        val messageId = fields.messageId
        val botUserId = fields.botUserId
        val callbackData = fields.callbackData
        val bot = com.maodouchat.server.repository.BotRepository.get(botUserId)
        if (bot == null || !bot.enabled) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot unavailable"))
        }
        val updateId = "cbq_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("update_type", kotlinx.serialization.json.JsonPrimitive("callback_query"))
            put("callback_query", kotlinx.serialization.json.buildJsonObject {
                put("id", kotlinx.serialization.json.JsonPrimitive(updateId))
                put("from", kotlinx.serialization.json.JsonPrimitive(userId))
                put("chatId", kotlinx.serialization.json.JsonPrimitive(chatId))
                put("messageId", kotlinx.serialization.json.JsonPrimitive(messageId))
                put("data", kotlinx.serialization.json.JsonPrimitive(callbackData))
            })
        }.toString()
        if (!com.maodouchat.server.repository.BotRepository.enqueueCallbackIfAuthorized(
                chatId = chatId,
                userId = userId,
                botId = bot.id,
                messageId = messageId,
                callbackData = callbackData,
                updateJson = payload
            )
        ) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("回调按钮无效或已不可用"))
        }
        // Targeted webhook for this bot only (avoid fan-out double enqueue).
        try {
            com.maodouchat.server.service.BotWebhookService.notifyBotDirect(
                botId = bot.id,
                bodyJson = payload
            )
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        call.respond(
        buildJsonObject {
put("ok", true)
put("callbackQueryId", updateId)
        }
    )
    }
}
