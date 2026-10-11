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

/** Bot 群成员限制·禁言（restrictChatMember；从 BotMemberRestrictRoutes 按域拆出）。 */
internal fun Route.configureBotMemberRestrictMuteRoutes(
    groupModerationRepo: GroupModerationRepository,
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

}
