package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.GroupInvitationService
import com.maodouchat.server.service.GroupMembershipService
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Bot 成员解封与角色调整（unbanChatMember / promoteChatMember）。 */
internal fun Route.configureBotMemberPromotionRoutes(
    groupMembershipService: GroupMembershipService,
    groupInvitationService: GroupInvitationService,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
) {

    post("/api/bot/unbanChatMember") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId: String
        val userId: String
        when (val parsed = parseBotUnbanChatMemberFields(obj)) {
            is BotUnbanChatMemberFieldsResult.Ok -> {
                chatId = parsed.fields.chatId
                userId = parsed.fields.userId
            }
            BotUnbanChatMemberFieldsResult.Invalid ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/userId required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val maxMembers = try {
            com.maodouchat.server.service.RuntimeConfigService.maxGroupSize()
        } catch (_: Exception) { 200 }
        val chatType = conversationParticipantRepo.chatType(chatId)
        val addedUserIds: List<String>
        val mutation: com.maodouchat.server.repository.GroupMemberMutationResult
        if (chatType == ChatType.CHANNEL) {
            val addCommit = groupMembershipService.addMembers(
                chatId = chatId,
                actorId = bot.id,
                requestedUserIds = listOf(userId),
                maxMembers = maxMembers,
                requireBotDeliverable = true
            )
            mutation = addCommit.result.result
            addedUserIds = addCommit.result.addedUserIds
        } else {
            val inviteResult = groupInvitationService.inviteMembers(
                chatId = chatId,
                actorId = bot.id,
                requestedUserIds = listOf(userId),
                maxMembers = maxMembers
            )
            mutation = inviteResult.result
            addedUserIds = inviteResult.invitedUserIds
        }
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, userId, "unbanChatMember")
        if (mutation != com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("unban failed: $mutation"))
        }
        if (chatType == ChatType.CHANNEL) {
            notifyGroupRevisionChanged(
                queryRepository = conversationQueryRepo,
                participantRepository = conversationParticipantRepo,
                json = json,
                chatId = chatId,
                reason = "MEMBER_ADDED",
                actorId = bot.id,
                targetUserId = userId
            )
        }
        call.respond(
        buildJsonObject {
put("ok", true)
put("userId", userId)
put("readded", chatType == ChatType.CHANNEL)
put("invited", chatType != ChatType.CHANNEL)
putJsonElement("added", addedUserIds)
        }
    )
    }

    post("/api/bot/promoteChatMember") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
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
