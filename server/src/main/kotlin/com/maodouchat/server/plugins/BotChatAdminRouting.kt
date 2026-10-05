package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 群资料管理（setChatTitle / setChatDescription / getChatAdministrators）。 */
internal fun Route.configureBotChatAdminRoutes(
    groupProfileRepo: GroupProfileRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
) {

    post("/api/bot/setChatTitle") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val chatId: String
        val title: String
        when (val parsed = parseBotSetChatTitleFields(obj)) {
            is BotSetChatTitleFieldsResult.Ok -> {
                chatId = parsed.fields.chatId
                title = parsed.fields.title
            }
            BotSetChatTitleFieldsResult.Invalid ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/title required (1-50)"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val mutation = groupProfileRepo.updateName(
            chatId = chatId,
            actorId = bot.id,
            name = title,
            requireBotDeliverable = true
        )
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "setChatTitle")
        if (mutation != com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("set title failed: $mutation"))
        }
        notifyGroupRevisionChanged(conversationQueryRepo, conversationParticipantRepo, json, chatId, "GROUP_RENAMED", bot.id)
        call.respond(
        buildJsonObject {
put("ok", true)
put("chatId", chatId)
put("title", title)
        }
    )
    }

    post("/api/bot/setChatDescription") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val chatId: String
        val description: String
        when (val parsed = parseBotSetChatDescriptionFields(obj)) {
            is BotSetChatDescriptionFieldsResult.Ok -> {
                chatId = parsed.fields.chatId
                description = parsed.fields.description
            }
            BotSetChatDescriptionFieldsResult.Invalid ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
            BotSetChatDescriptionFieldsResult.TooLong ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("description too long"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val announcement = description.takeIf { it.isNotBlank() }
        val mutation = groupProfileRepo.updateAnnouncement(
            chatId = chatId,
            actorId = bot.id,
            announcement = announcement,
            requireBotDeliverable = true
        )
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "setChatDescription")
        if (mutation != com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("set description failed: $mutation"))
        }
        notifyGroupRevisionChanged(conversationQueryRepo, conversationParticipantRepo, json, chatId, "ANNOUNCEMENT_UPDATED", bot.id)
        call.respond(
        buildJsonObject {
put("ok", true)
put("chatId", chatId)
put("description", (announcement ?: ""))
        }
    )
    }

    get("/api/bot/getChatAdministrators") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val chatId = call.requireNonBlankParamOr400("chatId", "chatId required") ?: return@get
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val admins = conversationParticipantRepo.groupMembers(chatId)
            .filter { it.role == "OWNER" || it.role == "ADMIN" }
        // 8.46：混合类型 List<Map<String,Any>> encodeToString 运行时抛 SerializationException → buildJsonArray
        val adminsArray = buildJsonArray {
            admins.forEach { m ->
                add(buildJsonObject {
                    put("userId", m.userId)
                    put("name", m.name)
                    put("role", m.role)
                    put("title", m.title ?: "")
                    put("isOnline", m.isOnline)
                })
            }
        }
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "getChatAdministrators")
        call.respond(
        buildJsonObject {
put("chatId", chatId)
put("administrators", adminsArray)
put("count", admins.size)
        }
    )
    }
}
