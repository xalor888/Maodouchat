package com.maodouchat.server.plugins

import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.BlobStore
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 邀请链接/群头像/取消置顶（自 BotCoreRouting.kt 拆分，B12）。 */
internal fun Route.configureBotChatInviteRoutes(
    userRepo: UserRepository,
    pinnedMessageRepo: PinnedMessageRepository,
    serviceMessageRepo: ServiceMessageRepository,
    groupProfileRepo: GroupProfileRepository,
    groupInvitationService: GroupInvitationService,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {

    post("/api/bot/unpinAllChatMessages") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.repository.BotRepository.isBotDeliverable(bot.id)) {
            return@post call.respondBotUnavailable()
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        if (chatId.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val chat = conversationQueryRepo.getById(chatId)
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("chat not found"))
        val actorIsManager = if (chat.isGroup) conversationParticipantRepo.isOwnerOrAdmin(chatId, bot.id) else true
        val outcome = pinnedMessageRepo.clearAll(
            chatId = chatId,
            actorId = bot.id,
            actorIsManager = actorIsManager,
            requireBotDeliverable = true
        )
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "unpinAllChatMessages")
        if (outcome.result == com.maodouchat.server.repository.PinnedMessageRepository.PinResult.FORBIDDEN) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        }
        if (outcome.result == com.maodouchat.server.repository.PinnedMessageRepository.PinResult.NOT_FOUND) {
            return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("chat not found"))
        }
        val payload = PinnedMessagesUpdatedPayload(chatId, bot.id, outcome.pins)
        val pinJson = json.encodeToString(
            WsMessage.serializer(),
            WsMessage(
                "PINNED_MESSAGES_UPDATED",
                json.encodeToString(PinnedMessagesUpdatedPayload.serializer(), payload)
            )
        )
        val fanoutPids = conversationParticipantRepo.participantIds(chatId)
        val botBlockedIds = try { userRepo.blockedEitherWayIdsInTx(bot.id, fanoutPids) } catch (_: Exception) { emptySet() }
        fanoutPids.forEach { pid ->
            if (pid in botBlockedIds) return@forEach
            LocalRealtimeBus.publish(pid, pinJson)
        }
        call.respond(
        buildJsonObject {
put("ok", true)
put("chatId", chatId)
put("pins", Json.parseToJsonElement(Json.encodeToString(outcome.pins)))
put("count", 0)
        }
    )
    }

    configureBotPollRoutes(
        userRepo = userRepo,
        serviceMessageRepo = serviceMessageRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )

    post("/api/bot/exportChatInviteLink") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isGroupInvitesEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("group_invites_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val rotate = obj["rotate"]?.jsonPrimitive?.booleanOrNull == true
        val expiresIn = (obj["expiresInSeconds"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: 7L * 24 * 3600).coerceIn(300L, 30L * 24 * 3600)
        val maxUses = (obj["maxUses"]?.jsonPrimitive?.content?.toIntOrNull() ?: 100).coerceIn(1, 1000)
        if (chatId.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
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

    post("/api/bot/setChatPhoto") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val base64 = (obj["photoBase64"] ?: obj["base64Data"] ?: obj["photo"])?.jsonPrimitive?.content.orEmpty()
        if (chatId.isBlank() || base64.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/photoBase64 required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val avatarUrl = try {
            com.maodouchat.server.service.FileStorageService.saveGroupAvatar(base64, chatId)
        } catch (error: IllegalArgumentException) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "invalid photo"))
        }
        var committed = false
        try {
            val mutation = groupProfileRepo.updateAvatar(
                chatId = chatId,
                actorId = bot.id,
                avatarUrl = avatarUrl,
                requireBotDeliverable = true
            )
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "setChatPhoto")
            if (mutation.result != com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("set photo failed: ${mutation.result}"))
            }
            committed = true
            com.maodouchat.server.service.FileStorageService.deleteGroupAvatarUrl(mutation.previousAvatarUrl, chatId)
            notifyGroupRevisionChanged(conversationQueryRepo, conversationParticipantRepo, json, chatId, "AVATAR_UPDATED", bot.id)
            call.respond(
        buildJsonObject {
put("ok", true)
put("chatId", chatId)
put("avatarUrl", avatarUrl)
        }
    )
        } finally {
            if (!committed) {
                com.maodouchat.server.service.FileStorageService.deleteGroupAvatarUrl(avatarUrl, chatId)
            }
        }
    }


    post("/api/bot/revokeChatInviteLink") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isGroupInvitesEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("group_invites_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        if (chatId.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
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

    post("/api/bot/deleteChatPhoto") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        if (chatId.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val mutation = groupProfileRepo.clearAvatar(
            chatId = chatId,
            actorId = bot.id,
            requireBotDeliverable = true
        )
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "deleteChatPhoto")
        if (mutation.result != com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("delete photo failed: ${mutation.result}"))
        }
        com.maodouchat.server.service.FileStorageService.deleteGroupAvatarUrl(mutation.previousAvatarUrl, chatId)
        notifyGroupRevisionChanged(conversationQueryRepo, conversationParticipantRepo, json, chatId, "AVATAR_CLEARED", bot.id)
        call.respond(
        buildJsonObject {
put("ok", true)
put("chatId", chatId)
put("cleared", true)
        }
    )
    }
}
