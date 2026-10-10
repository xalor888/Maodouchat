package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.GroupMembershipService
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 群管理类端点（邀请链接/降级成员/群权限）。 */
internal fun Route.configureBotMediaGroupRoutes(
    userRepo: UserRepository,
    serviceMessageRepo: ServiceMessageRepository,
    groupMembershipService: GroupMembershipService,
    groupModerationRepo: GroupModerationRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {

    get("/api/bot/getInviteLink") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        if (!com.maodouchat.server.service.RuntimeConfigService.isGroupInvitesEnabled()) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("group_invites_disabled"))
        }
        val chatId = parseBotGetInviteLinkChatId(call.request.queryParameters)
            ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        // 9.242：邀请 token 是管理者级信息（持 token 可拉人入群）——与 pin/unpin 的
        // isOwnerOrAdmin 口径对齐（当前 bot 入群即 ADMIN，防御未来成员角色变化）
        if (!conversationParticipantRepo.isOwnerOrAdmin(chatId, bot.id)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot is not a manager of this chat"))
        }
        // Read-only invite snapshot; rotation uses exportChatInviteLink
        val chat = conversationQueryRepo.getById(chatId)
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("chat not found"))
        val tokenState = conversationQueryRepo.getGroupInviteTokenState(chatId)
        val invite = tokenState?.token.orEmpty()
        val expiresAt = tokenState?.expiresAt ?: 0L
        val maxUses = tokenState?.maxUses ?: 0
        val used = tokenState?.usedCount ?: 0
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "getInviteLink")
        call.respond(
        buildJsonObject {
put("chatId", chatId)
put("title", (chat.groupName ?: ""))
put("inviteToken", invite)
put("inviteLink", if (invite.isNotBlank()) "maodouchat:chat-invite:v1:$invite" else "")
put("expiresAt", expiresAt)
put("maxUses", maxUses)
put("usedCount", used)
put("hasInvite", invite.isNotBlank())
        }
    )
    }

    post("/api/bot/demoteChatMember") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val chatId: String
        val userId: String
        when (val parsed = parseBotDemoteChatMemberFields(obj)) {
            is BotDemoteChatMemberFieldsResult.Ok -> {
                chatId = parsed.fields.chatId
                userId = parsed.fields.userId
            }
            BotDemoteChatMemberFieldsResult.Invalid ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/userId required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val commit = groupMembershipService.updateRole(
            chatId = chatId,
            ownerId = bot.id,
            targetUserId = userId,
            role = "MEMBER",
            requireBotDeliverable = true
        )
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, userId, "demoteChatMember")
        if (commit.result != com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("demote failed: ${commit.result}"))
        }
        notifyGroupRevisionChangedWithData(
            json = json,
            chatId = chatId,
            reason = "MEMBER_ROLE_CHANGED",
            actorId = bot.id,
            targetUserId = userId,
            memberRevision = commit.memberRevisionAfter ?: 0L,
            recipientIds = commit.recipientsBefore,
        )
        call.respond(
        buildJsonObject {
put("ok", true)
put("userId", userId)
put("role", "MEMBER")
        }
    )
    }

    post("/api/bot/setChatPermissions") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val chatId: String
        val canSend: Boolean
        val until: Long
        when (val parsed = parseBotSetChatPermissionsFields(obj)) {
            is BotSetChatPermissionsFieldsResult.Ok -> {
                chatId = parsed.fields.chatId
                canSend = parsed.fields.canSend
                until = parsed.fields.until
            }
            BotSetChatPermissionsFieldsResult.Invalid ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/canSendMessages required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        // Group default: mute-all via member mute of non-admins is not stored as a single flag.
        // Approximate Telegram setChatPermissions by muting all non-admin members when canSend=false.
        val members = conversationParticipantRepo.participantIds(chatId)
        val muteUntil = if (canSend) 0L else {
            if (until > System.currentTimeMillis()) until
            else System.currentTimeMillis() + 24L * 3600_000L
        }
        // 8.48 修复 M8：一次事务批量静音非管理员成员（此前逐成员 isOwnerOrAdmin +
        // 独立事务静音 ≈5 次查询/人，500 人群 ≈2500 次）
        val nonBotMembers = members.filter { it != bot.id }
        val bulkMutation = if (nonBotMembers.isEmpty()) {
            GroupBulkMuteResult(GroupMemberMutationResult.UPDATED)
        } else {
            groupModerationRepo.updateMembersMute(
            chatId = chatId,
            actorId = bot.id,
            targetUserIds = nonBotMembers,
            mutedUntil = muteUntil,
            requireBotDeliverable = true
        )
        }
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "setChatPermissions")
        if (bulkMutation.result != GroupMemberMutationResult.UPDATED) {
            return@post call.respond(
                HttpStatusCode.Forbidden,
                ErrorResponse("set permissions failed: ${bulkMutation.result}")
            )
        }
        val changed = bulkMutation.updatedCount
        if (changed > 0) {
            notifyGroupRevisionChanged(conversationQueryRepo, conversationParticipantRepo, json, chatId, "CHAT_PERMISSIONS", bot.id)
        }
        call.respond(
        buildJsonObject {
put("ok", true)
put("chatId", chatId)
put("canSendMessages", canSend)
put("muteUntil", muteUntil)
put("membersUpdated", changed)
        }
    )
    }
}
