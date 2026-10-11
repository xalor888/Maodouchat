package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.GroupMembershipService
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 群管理：邀请链接读（从 BotMediaGroupRoutes 按域拆出）。 */
internal fun Route.configureBotMediaGroupInviteRoutes(
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
}
