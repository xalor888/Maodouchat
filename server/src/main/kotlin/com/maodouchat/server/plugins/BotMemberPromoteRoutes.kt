package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.GroupMembershipService
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 群成员角色提升（promoteChatMember）。 */
internal fun Route.configureBotMemberPromoteRoutes(
    groupMembershipService: GroupMembershipService,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
) {

    post("/api/bot/promoteChatMember") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val chatId: String
        val userId: String
        val role: String
        when (val parsed = parseBotPromoteChatMemberFields(obj)) {
            is BotPromoteChatMemberFieldsResult.Ok -> {
                chatId = parsed.fields.chatId
                userId = parsed.fields.userId
                role = parsed.fields.role
            }
            BotPromoteChatMemberFieldsResult.Invalid ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/userId required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        // Only OWNER can change roles; bots invited as ADMIN cannot promote — require owner bot or use updateGroupMemberRoleAsOwner
        val commit = groupMembershipService.updateRole(
            chatId = chatId,
            ownerId = bot.id,
            targetUserId = userId,
            role = role,
            requireBotDeliverable = true
        )
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, userId, "promoteChatMember")
        if (commit.result != com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("role update failed: ${commit.result}"))
        }
        notifyGroupRevisionChangedWithData(
            json = json,
            chatId = chatId,
            reason = "ROLE_UPDATED",
            actorId = bot.id,
            targetUserId = userId,
            memberRevision = commit.memberRevisionAfter ?: 0L,
            recipientIds = commit.recipientsBefore,
        )
        call.respond(
        buildJsonObject {
put("ok", true)
put("userId", userId)
put("role", role)
        }
    )
    }
}
