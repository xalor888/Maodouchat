package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.enqueueUserCommand
import com.maodouchat.server.service.RuntimeConfigService
import com.maodouchat.server.service.BotWebhookService
import com.maodouchat.server.service.isBotsAllowed
import com.maodouchat.server.service.notifyBotDirect
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 用户命令收件箱：群聊内 @bot 指令收集与投递。 */
internal fun Route.configureBotInteractionInboxRoutes(
    userRepo: UserRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    botCreateRateLimiter: BoundedRateLimiter,
) {

    post("/api/chats/{chatId}/bot-inbox") {
        if (call.rejectIfMaintenance()) return@post
        val userId = call.requireUserId()
        if (call.rejectIfSuspended(userRepo, userId)) return@post
        if (call.rejectIfMessageRestricted(userRepo, userId)) return@post
        if (!RuntimeConfigService.isBotsAllowed()) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot platform disabled"))
            return@post
        }
        val chatId = call.requirePathParamOr400("chatId", "缺少聊天 ID") ?: return@post
        if (!conversationParticipantRepo.isParticipant(chatId, userId)) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该聊天"))
            return@post
        }
        if (!botCreateRateLimiter.acquire("bot-inbox:$userId", maxPerMinute = 60)) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
            return@post
        }
        val body = call.receiveBoundedTextOrEmpty(8_192)
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val inbox = parseBotInboxFields(obj)
        val text = inbox.text
        val botIdHint = inbox.botIdHint
        val cleaned = com.maodouchat.server.bot.BotCommandPolicy.sanitizeInboxText(text)
        if (cleaned == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("命令无效或不能是密文"))
            return@post
        }
        val delivered = com.maodouchat.server.repository.BotRepository.enqueueUserCommand(
            chatId = chatId,
            userId = userId,
            text = cleaned,
            botIdHint = botIdHint
        )
        if (delivered.isEmpty()) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("该会话没有可接收命令的机器人"))
            return@post
        }
        delivered.forEach { (botId, payload) ->
            try {
                BotWebhookService.notifyBotDirect(
                    botId = botId,
                    bodyJson = payload
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        call.respond(
            buildJsonObject {
                put("ok", true)
                put("delivered", delivered.size)
            }
        )
    }
}
