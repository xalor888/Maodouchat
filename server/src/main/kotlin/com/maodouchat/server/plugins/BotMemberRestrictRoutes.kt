package com.maodouchat.server.plugins

import com.maodouchat.server.model.ChatType
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.GroupMembershipService
import com.maodouchat.server.service.GroupInvitationService
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 群成员限制（restrictChatMember / banChatMember / unbanChatMember）。 */
internal fun Route.configureBotMemberRestrictRoutes(
    groupMembershipService: GroupMembershipService,
    groupModerationRepo: GroupModerationRepository,
    groupInvitationService: GroupInvitationService,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
) {

    post("/api/bot/restrictChatMember") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val chatId: String
        val userId: String
        var until: Long
        when (val parsed = parseBotRestrictChatMemberFields(obj)) {
            is BotRestrictChatMemberFieldsResult.Ok -> {
                chatId = parsed.fields.chatId
                userId = parsed.fields.userId
                until = parsed.fields.until
            }
            BotRestrictChatMemberFieldsResult.Invalid ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/userId required"))
        }
        // 8.33 修复：与用户端 mute 路由一致，禁言时长上限 30 天（此前 bot 可无限期禁言）
        val nowMs = System.currentTimeMillis()
        if (until > 0L) {
            if (until <= nowMs) until = 0L
            else if (until > nowMs + MAX_MUTE_DURATION_MS) until = nowMs + MAX_MUTE_DURATION_MS
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        // Promote bot role temporarily for mute: treat bot as ADMIN if owner invited as member
        // Use owner path: if bot role is MEMBER, still try mute only when bot is elevated — promote on invite.
        val mutation = groupModerationRepo.updateMemberMute(
            chatId = chatId,
            actorId = bot.id,
            targetUserId = userId,
            mutedUntil = until,
            requireBotDeliverable = true
        )
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, userId, "restrictChatMember")
        if (mutation != com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("restrict failed: $mutation"))
        }
        notifyGroupRevisionChanged(conversationQueryRepo, conversationParticipantRepo, json, chatId, "MEMBER_MUTED", bot.id, userId)
        call.respond(
        buildJsonObject {
put("ok", true)
put("userId", userId)
put("mutedUntil", until)
        }
    )
    }

    post("/api/bot/banChatMember") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val chatId: String
        val userId: String
        when (val parsed = parseBotBanChatMemberFields(obj)) {
            is BotBanChatMemberFieldsResult.Ok -> {
                chatId = parsed.fields.chatId
                userId = parsed.fields.userId
            }
            BotBanChatMemberFieldsResult.Invalid ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/userId required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val commit = groupMembershipService.removeMember(
            chatId = chatId,
            actorId = bot.id,
            targetUserId = userId,
            requireBotDeliverable = true
        )
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, userId, "banChatMember")
        if (commit.result != com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("ban failed: ${commit.result}"))
        }
        notifyGroupRevisionChangedWithData(
            json = json,
            chatId = chatId,
            reason = "MEMBER_REMOVED",
            actorId = bot.id,
            targetUserId = userId,
            memberRevision = commit.memberRevisionAfter ?: 0L,
            recipientIds = commit.recipientsBefore,
        )
        call.respond(
        buildJsonObject {
put("ok", true)
put("userId", userId)
put("removed", true)
        }
    )
    }
    post("/api/bot/unbanChatMember") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
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
}
