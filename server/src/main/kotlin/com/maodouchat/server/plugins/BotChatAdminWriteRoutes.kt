package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import com.maodouchat.server.repository.updateAnnouncement
import com.maodouchat.server.repository.updateName

/** Bot 群资料写操作（setChatTitle / setChatDescription）。 */
internal fun Route.configureBotChatAdminWriteRoutes(
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
}
