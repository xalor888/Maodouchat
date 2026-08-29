package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.AddOwnedBotResult
import com.maodouchat.server.repository.ConversationCreationRepository
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.GroupMembershipRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Bot 交互（用户侧）子域路由：会话内 bot 命令/收件箱、私聊 bot、回调、加 bot 进群。
 * 从 configureRouting 抽出，只做鉴权/DTO/校验/调用 BotRepository/GroupMembershipRepository。
 */
internal fun Route.configureBotInteractionRoutes(
    userRepo: UserRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationCreationRepo: ConversationCreationRepository,
    conversationQueryRepo: ConversationQueryRepository,
    groupMembershipRepo: GroupMembershipRepository,
    botCreateRateLimiter: BoundedRateLimiter,
    createChatRateLimiter: BoundedRateLimiter,
    json: Json,
) {

    get("/api/chats/{chatId}/bot-commands") {
        val userId = call.principal<JWTPrincipal>()!!.payload.subject
        val chatId = call.parameters["chatId"]!!
        if (!conversationParticipantRepo.isParticipant(chatId, userId)) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该聊天"))
            return@get
        }
        call.respond(
            buildJsonObject {
                put("ok", true)
                putJsonArray("bots") {
                    com.maodouchat.server.repository.BotRepository.listEnabledBotsInChat(chatId).forEach { bot ->
                        add(
                            buildJsonObject {
                                put("id", bot.id)
                                put("username", bot.username)
                                put("name", bot.name)
                            }
                        )
                    }
                }
                putJsonArray("commands") {
                    com.maodouchat.server.repository.BotRepository.listCommandsForChat(chatId).forEach { item ->
                        add(
                            buildJsonObject {
                                put("botId", item.botId)
                                put("username", item.username)
                                put("name", item.name)
                                put("command", item.command)
                                put("description", item.description)
                            }
                        )
                    }
                }
            }
        )
    }

    post("/api/chats/{chatId}/bot-inbox") {
        if (call.rejectIfMaintenance()) return@post
        val userId = call.principal<JWTPrincipal>()!!.payload.subject
        if (call.rejectIfSuspended(userRepo, userId)) return@post
        if (call.rejectIfMessageRestricted(userRepo, userId)) return@post
        if (!RuntimeConfigService.isBotsAllowed()) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot platform disabled"))
            return@post
        }
        val chatId = call.parameters["chatId"]!!
        if (!conversationParticipantRepo.isParticipant(chatId, userId)) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该聊天"))
            return@post
        }
        if (!botCreateRateLimiter.acquire("bot-inbox:$userId", maxPerMinute = 60)) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
            return@post
        }
        val body = call.receiveBoundedTextOrEmpty(8_192)
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val text = (obj["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        val botIdHint = (obj["botId"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
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
        val userId = call.principal<JWTPrincipal>()!!.payload.subject
        if (call.rejectIfSuspended(userRepo, userId)) return@post
        if (!RuntimeConfigService.isBotsAllowed()) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot platform disabled"))
            return@post
        }
        val botId = call.parameters["botId"]!!
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
            conversationCreationRepo.getOrCreateDirect(userId, botId)
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
        val userId = call.principal<JWTPrincipal>()!!.payload.subject
        // 8.33 修复：封禁用户不得触发 bot 回调（bot 平台交互面一致收口）
        if (call.rejectIfSuspended(userRepo, userId)) return@post
        val chatId = call.parameters["chatId"]!!
        if (!conversationParticipantRepo.isParticipant(chatId, userId)) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该聊天"))
            return@post
        }
        val body = call.receiveBoundedTextOrEmpty(16_384)
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val messageId = obj["messageId"]?.jsonPrimitive?.content.orEmpty()
        val botUserId = obj["botUserId"]?.jsonPrimitive?.content.orEmpty()
        val callbackData = obj["callbackData"]?.jsonPrimitive?.content.orEmpty()
        if (messageId.isBlank() || messageId.length > 80 ||
            botUserId.isBlank() || botUserId.length > 80 ||
            callbackData.isBlank() || callbackData.length > 128
        ) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("messageId/botUserId/callbackData required"))
        }
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

post("/api/chats/{chatId}/bots") {
        val userId = call.principal<JWTPrincipal>()!!.payload.subject
        if (call.rejectIfMaintenance()) return@post
        if (!RuntimeConfigService.isBotsAllowed()) {
            // 8.32 一致性：功能禁用统一 403（与 nearby/posts/chat_folders 等 disabled 语义一致）
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot platform disabled"))
        }
        if (call.rejectIfSuspended(userRepo, userId)) return@post
        val chatId = call.parameters["chatId"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing chatId"))
        val body = call.receiveBoundedTextOrEmpty(4_096)
        val botId = runCatching { Json.parseToJsonElement(body).jsonObject["botId"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
        if (botId.isBlank() || botId.length > 80) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("botId required"))
        }
        val addResult = groupMembershipRepo.addOwnedBot(chatId, userId, botId, maxGroupMembers())
        when (addResult) {
            AddOwnedBotResult.ADDED ->
                notifyGroupRevisionChanged(conversationQueryRepo, conversationParticipantRepo, json, chatId, "BOT_ADDED", userId, botId)
            AddOwnedBotResult.ALREADY_MEMBER -> Unit
            AddOwnedBotResult.CHAT_NOT_FOUND ->
                return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("群聊不存在"))
            AddOwnedBotResult.NOT_GROUP ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("只能向群聊邀请机器人"))
            AddOwnedBotResult.FORBIDDEN ->
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("仅群主或管理员可邀请机器人"))
            AddOwnedBotResult.BOT_NOT_FOUND ->
                return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("bot not found"))
            AddOwnedBotResult.BOT_NOT_OWNED ->
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("只能邀请自己的机器人"))
            AddOwnedBotResult.BOT_DISABLED ->
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot disabled"))
            AddOwnedBotResult.MEMBER_LIMIT_EXCEEDED ->
                return@post call.respond(HttpStatusCode.Conflict, ErrorResponse("群成员已达上限"))
        }
        val bot = com.maodouchat.server.repository.BotRepository.get(botId)
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("bot not found"))
        if (addResult == AddOwnedBotResult.ADDED) {
            com.maodouchat.server.service.BotWebhookService.notifyChatEvent(
                chatId = chatId,
                event = "bot_added",
                senderId = userId,
                type = "SYSTEM",
                textPreview = "bot ${bot.username} added"
            )
        }
        call.respond(
        buildJsonObject {
put("ok", true)
put("botId", botId)
        }
    )
    }
}