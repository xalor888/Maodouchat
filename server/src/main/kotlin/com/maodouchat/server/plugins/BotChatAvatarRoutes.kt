package com.maodouchat.server.plugins

import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.GroupProfileRepository
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.maodouchat.server.model.ErrorResponse

/** Bot 群头像：设置 / 删除。 */
internal fun Route.configureBotChatAvatarRoutes(
    groupProfileRepo: GroupProfileRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    post("/api/bot/setChatPhoto") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val fields = when (val parsed = parseBotSetChatPhotoFields(obj)) {
            is BotSetChatPhotoFieldsResult.Ok -> parsed.fields
            BotSetChatPhotoFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/photoBase64 required"))
        }
        val chatId = fields.chatId
        val base64 = fields.base64
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val avatarUrl = try {
            com.maodouchat.server.service.FileStorageService.saveGroupAvatar(base64, chatId)
        } catch (error: IllegalArgumentException) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "invalid photo"))
        }
        var committed = false
        try {
            val mutation = groupProfileRepo.updateAvatar(
                chatId = chatId,
                actorId = bot.id,
                avatarUrl = avatarUrl,
                requireBotDeliverable = true
            )
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "setChatPhoto")
            if (mutation.result != com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("set photo failed: ${mutation.result}"))
            }
            committed = true
            com.maodouchat.server.service.FileStorageService.deleteGroupAvatarUrl(mutation.previousAvatarUrl, chatId)
            notifyGroupRevisionChanged(conversationQueryRepo, conversationParticipantRepo, json, chatId, "AVATAR_UPDATED", bot.id)
            call.respond(
        buildJsonObject {
put("ok", true)
put("chatId", chatId)
put("avatarUrl", avatarUrl)
        }
    )
        } finally {
            if (!committed) {
                com.maodouchat.server.service.FileStorageService.deleteGroupAvatarUrl(avatarUrl, chatId)
            }
        }
    }

    post("/api/bot/deleteChatPhoto") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val chatId = when (val parsed = parseBotDeleteChatPhotoFields(obj)) {
            is BotDeleteChatPhotoFieldsResult.Ok -> parsed.fields.chatId
            BotDeleteChatPhotoFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val mutation = groupProfileRepo.clearAvatar(
            chatId = chatId,
            actorId = bot.id,
            requireBotDeliverable = true
        )
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "deleteChatPhoto")
        if (mutation.result != com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("delete photo failed: ${mutation.result}"))
        }
        com.maodouchat.server.service.FileStorageService.deleteGroupAvatarUrl(mutation.previousAvatarUrl, chatId)
        notifyGroupRevisionChanged(conversationQueryRepo, conversationParticipantRepo, json, chatId, "AVATAR_CLEARED", bot.id)
        call.respond(
        buildJsonObject {
put("ok", true)
put("chatId", chatId)
put("cleared", true)
        }
    )
    }
}
