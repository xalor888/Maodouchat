package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.AddOwnedBotResult
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.service.GroupMembershipService
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bot 交互·管理：向群聊添加自有 bot。 */
internal fun Route.configureBotInteractionManageRoutes(
    userRepo: UserRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    groupMembershipService: GroupMembershipService,
    json: Json,
) {

        post("/api/chats/{chatId}/bots") {
                val userId = call.requireUserId()
                if (call.rejectIfMaintenance()) return@post
                if (!RuntimeConfigService.isBotsAllowed()) {
                    // 8.32 一致性：功能禁用统一 403（与 nearby/posts/chat_folders 等 disabled 语义一致）
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot platform disabled"))
                }
                if (call.rejectIfSuspended(userRepo, userId)) return@post
                val chatId = call.requirePathParamOr400("chatId", "missing chatId") ?: return@post
                val body = call.receiveBoundedTextOrEmpty(4_096)
                val botId = parseAddBotToChatBotId(body)
                if (botId.isBlank() || botId.length > 80) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("botId required"))
                }
                val addCommit = groupMembershipService.addOwnedBot(chatId, userId, botId, maxGroupMembers())
                val addResult = addCommit.result
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
