package com.maodouchat.server.plugins

import com.maodouchat.server.model.ChatType
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.service.GroupInvitationService
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.maodouchat.server.model.ErrorResponse

/** Bot 群邀请链接：导出 / 撤销。 */
internal fun Route.configureBotChatInviteLinkRoutes(
    groupInvitationService: GroupInvitationService,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    post("/api/bot/exportChatInviteLink") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isGroupInvitesEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("group_invites_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val fields = when (val parsed = parseBotExportChatInviteLinkFields(obj)) {
            is BotExportChatInviteLinkFieldsResult.Ok -> parsed.fields
            BotExportChatInviteLinkFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        }
        val chatId = fields.chatId
        val rotate = fields.rotate
        val expiresIn = fields.expiresInSeconds
        val maxUses = fields.maxUses
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        // 8.63：广播频道不开放邀请加入——与 App 侧 invite-token 路由一致拦截（此前 Bot 可给频道生成邀请）
        if (conversationParticipantRepo.chatType(chatId) == ChatType.CHANNEL) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("频道不支持邀请加入"))
        }
        val expiresAt = System.currentTimeMillis() + expiresIn * 1000L
        val mutation = groupInvitationService.configureToken(
            chatId = chatId,
            actorId = bot.id,
            rotate = rotate,
            expiresAt = expiresAt,
            maxUses = maxUses,
            requireBotDeliverable = true
        )
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "exportChatInviteLink")
        if (mutation.result != com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("invite failed: ${mutation.result}"))
        }
        val inv = mutation.invite
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invite missing"))
        call.respond(
        buildJsonObject {
put("ok", true)
put("chatId", chatId)
put("inviteLink", "maodouchat:chat-invite:v1:${inv.token}")
put("token", inv.token)
put("expiresAt", inv.expiresAt)
put("maxUses", inv.maxUses)
put("usedCount", inv.usedCount)
        }
    )
    }

    post("/api/bot/revokeChatInviteLink") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isGroupInvitesEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("group_invites_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val fields = when (val parsed = parseBotRevokeChatInviteLinkFields(obj)) {
            is BotRevokeChatInviteLinkFieldsResult.Ok -> parsed.fields
            BotRevokeChatInviteLinkFieldsResult.MissingRequired ->
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        }
        val chatId = fields.chatId
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val mutation = groupInvitationService.revokeToken(
            chatId = chatId,
            actorId = bot.id,
            requireBotDeliverable = true
        )
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "revokeChatInviteLink")
        if (mutation != com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("revoke failed: $mutation"))
        }
        notifyGroupRevisionChanged(conversationQueryRepo, conversationParticipantRepo, json, chatId, "INVITE_REVOKED", bot.id)
        call.respond(
        buildJsonObject {
put("ok", true)
put("chatId", chatId)
put("revoked", true)
        }
    )
    }
}
