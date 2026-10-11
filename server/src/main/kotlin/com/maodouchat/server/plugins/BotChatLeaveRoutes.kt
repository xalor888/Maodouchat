package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.LeaveConversationResult
import com.maodouchat.server.service.BlobStore
import com.maodouchat.server.service.ConversationCommandService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bot 退出会话（leaveChat）。 */
internal fun Route.configureBotChatLeaveRoutes(
    commandService: ConversationCommandService,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
) {

    post("/api/bot/leaveChat") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val chatId = when (val parsed = parseBotLeaveChatFields(obj)) {
            is BotLeaveChatFieldsResult.Ok -> parsed.fields.chatId
            BotLeaveChatFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        }
        val outcome = runCatching {
            commandService.leave(chatId = chatId, userId = bot.id, requireBotDeliverable = true)
        }.getOrNull()
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "leaveChat")
        if (outcome?.result == LeaveConversationResult.OWNER_TRANSFER_REQUIRED) {
            return@post call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("群主需先转让群主身份再退出群聊", code = "GROUP_OWNER_TRANSFER_REQUIRED")
            )
        }
        if (outcome?.result == LeaveConversationResult.LEFT) {
            outcome.deletedAttachmentIds.forEach(BlobStore::delete)
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
}
