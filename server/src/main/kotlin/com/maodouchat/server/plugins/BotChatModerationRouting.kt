package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.EncryptedAttachmentStorage
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 会话生命周期与消息删除（leaveChat / deleteMessage）。 */
internal fun Route.configureBotChatModerationRoutes(
    conversationLifecycleRepo: ConversationLifecycleRepository,
    serviceMessageRepo: ServiceMessageRepository,
    userRepo: UserRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {

    post("/api/bot/leaveChat") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        if (chatId.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        val outcome = runCatching {
            conversationLifecycleRepo.leave(chatId = chatId, userId = bot.id, requireBotDeliverable = true)
        }.getOrNull()
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "leaveChat")
        if (outcome?.result == LeaveConversationResult.OWNER_TRANSFER_REQUIRED) {
            return@post call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("群主需先转让群主身份再退出群聊", code = "GROUP_OWNER_TRANSFER_REQUIRED")
            )
        }
        if (outcome?.result == LeaveConversationResult.LEFT) {
            outcome.deletedAttachmentIds.forEach(EncryptedAttachmentStorage::delete)
            com.maodouchat.server.service.FileStorageService.deleteGroupAvatarUrl(
                outcome.deletedGroupAvatarUrl,
                chatId,
            )
        }
        val revisionAfter = outcome?.memberRevisionAfter
        if (outcome?.wasGroup == true && outcome.result == LeaveConversationResult.LEFT && revisionAfter != null) {
            notifyGroupRevisionChangedWithData(
                json = json,
                chatId = chatId,
                reason = "MEMBER_LEFT",
                actorId = bot.id,
                targetUserId = bot.id,
                memberRevision = revisionAfter,
                recipientIds = outcome.recipientsBefore,
            )
        }
        call.respond(
        buildJsonObject {
put("status", "ok")
put("chatId", chatId)
put("result", (outcome?.result?.name ?: "UNKNOWN"))
        }
    )
    }

    post("/api/bot/deleteMessage") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val messageId = obj["messageId"]?.jsonPrimitive?.content.orEmpty()
        if (chatId.isBlank() || messageId.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/messageId required"))
        }
        val msg = serviceMessageRepo.getById(messageId)
        if (msg == null || msg.chatId != chatId) {
            return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("message not found"))
        }
        if (msg.senderId != bot.id) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("can only delete own bot messages"))
        }
        val deleted = runCatching {
            serviceMessageRepo.deleteOwn(messageId, bot.id)
        }.getOrNull()
        if (deleted != null) {
            fanoutBotEvent(
                userRepo = userRepo,
                participantRepository = conversationParticipantRepo,
                json = json,
                botId = bot.id,
                chatId = chatId,
                event = com.maodouchat.server.messaging.v2.ServiceMessagingV2Event(
                    action = "DELETE",
                    targetMessageId = messageId,
                ),
                messagingV2Repository = messagingV2Repository,
            )
        }
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, messageId, "deleteMessage")
        call.respond(
        buildJsonObject {
put("status", if (deleted != null) "ok" else "failed")
put("messageId", messageId)
        }
    )
    }
}
