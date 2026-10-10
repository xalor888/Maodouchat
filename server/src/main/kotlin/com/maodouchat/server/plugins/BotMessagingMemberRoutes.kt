package com.maodouchat.server.plugins

import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.GroupMembershipService
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 消息变体·成员：踢人 / 邀请链接。 */
internal fun Route.configureBotMessagingMemberRoutes(
    groupMembershipService: GroupMembershipService,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
) {

        post("/api/bot/kickChatMember") {
            // Alias of banChatMember for Telegram-compat naming
            val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
            val body = call.receiveBoundedTextOrEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val chatId: String
            val userId: String
            when (val parsed = parseBotKickChatMemberFields(obj)) {
                is BotKickChatMemberFieldsResult.Ok -> {
                    chatId = parsed.fields.chatId
                    userId = parsed.fields.userId
                }
                BotKickChatMemberFieldsResult.Invalid ->
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
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, userId, "kickChatMember")
            if (commit.result != com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("kick failed: ${commit.result}"))
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
    put("kicked", true)
            }
        )
        }

        get("/api/bot/getChatInviteLink") {
            // Alias of getInviteLink
            val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
            if (!com.maodouchat.server.service.RuntimeConfigService.isGroupInvitesEnabled()) {
                return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("group_invites_disabled"))
            }
            val chatId = call.requireNonBlankParamOr400("chatId", "chatId required") ?: return@get
            if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
                return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
            }
            // 9.242：同 getInviteLink——邀请 token 仅管理者可读
            if (!conversationParticipantRepo.isOwnerOrAdmin(chatId, bot.id)) {
                return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot is not a manager of this chat"))
            }
            val chat = conversationQueryRepo.getById(chatId)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("chat not found"))
            val tokenState = conversationQueryRepo.getGroupInviteTokenState(chatId)
            val invite = tokenState?.token.orEmpty()
            val expiresAt = tokenState?.expiresAt ?: 0L
            val maxUses = tokenState?.maxUses ?: 0
            val used = tokenState?.usedCount ?: 0
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "getChatInviteLink")
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
}
