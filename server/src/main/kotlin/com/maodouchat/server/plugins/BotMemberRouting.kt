package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.GroupMembershipService
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 群成员管理（getChatMember / restrictChatMember / banChatMember）。 */
internal fun Route.configureBotMemberRoutes(
    groupMembershipService: GroupMembershipService,
    groupModerationRepo: GroupModerationRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
) {

    get("/api/bot/getChatMember") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val chatId = parseRawOrEmpty(call.request.queryParameters, "chatId")
        val userId = parseRawOrEmpty(call.request.queryParameters, "userId")
        if (chatId.isBlank() || userId.isBlank()) {
            return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/userId required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val members = conversationParticipantRepo.groupMembers(chatId)
        val m = members.firstOrNull { it.userId == userId }
        if (m == null) {
            // 1:1 or non-group: check participant list
            val isMember = conversationParticipantRepo.isParticipant(chatId, userId)
            if (!isMember) return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("member not found"))
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, userId, "getChatMember")
            call.respond(
        buildJsonObject {
put("userId", userId)
put("status", "member")
put("role", "MEMBER")
        }
    )
        } else {
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, userId, "getChatMember")
            call.respond(
        buildJsonObject {
put("userId", m.userId)
put("name", m.name)
put("role", m.role)
put("title", (m.title ?: ""))
put("mutedUntil", m.mutedUntil)
put("status", if (m.mutedUntil > System.currentTimeMillis()) "restricted" else "member")
        }
    )
        }
    }

    post("/api/bot/restrictChatMember") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
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
        val obj = parseJsonObjectEnvelopeOrNull(body)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
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
}
