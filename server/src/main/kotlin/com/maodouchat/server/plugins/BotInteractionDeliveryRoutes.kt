package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.service.ConversationCommandService
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bot 交互·投递：用户命令收件箱、DM 建联、回调按钮。 */
internal fun Route.configureBotInteractionDeliveryRoutes(
    userRepo: UserRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    commandService: ConversationCommandService,
    conversationQueryRepo: ConversationQueryRepository,
    botCreateRateLimiter: BoundedRateLimiter,
    createChatRateLimiter: BoundedRateLimiter,
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
                    com.maodouchat.server.service.BotWebhookService.notifyBotDirect(
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

        post("/api/bots/{botId}/dm") {
            if (call.rejectIfMaintenance()) return@post
            val userId = call.requireUserId()
            if (call.rejectIfSuspended(userRepo, userId)) return@post
            if (!RuntimeConfigService.isBotsAllowed()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot platform disabled"))
                return@post
            }
            val botId = call.requirePathParamOr400("botId", "缺少机器人 ID") ?: return@post
            val bot = com.maodouchat.server.repository.BotRepository.get(botId)
            if (bot == null || !bot.enabled) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("bot not found"))
                return@post
            }
            if (!com.maodouchat.server.repository.BotRepository.isBotDeliverable(botId)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot unavailable"))
                return@post
            }
            if (!createChatRateLimiter.acquire(userId, maxPerMinute = 20)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("创建会话过于频繁，请稍后再试"))
                return@post
            }
            val created = try {
                commandService.getOrCreateDirect(userId, botId)
            } catch (_: IllegalArgumentException) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("无法与该机器人创建私聊"))
                return@post
            }
            val chat = conversationQueryRepo.getById(created.id, userId)
            if (chat == null) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("会话创建成功但读取失败，请刷新"))
                return@post
            }
            call.respond(HttpStatusCode.Created, chat)
        }

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
