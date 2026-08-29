package com.maodouchat.server.plugins

import com.maodouchat.server.auth.JwtConfig
import com.maodouchat.server.db.AuthSessions
import com.maodouchat.server.model.ChatType
import com.maodouchat.server.model.CreateChatRequest
import com.maodouchat.server.model.CreateGroupInviteRequest
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.GroupInviteResponse
import com.maodouchat.server.model.UpdateGroupAnnouncementRequest
import com.maodouchat.server.model.UpdateGroupNicknameRequest
import com.maodouchat.server.model.UpdateMemberMuteRequest
import com.maodouchat.server.model.UpdateMemberTitleRequest
import com.maodouchat.server.model.UploadAvatarRequest
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.GroupAuditRepository
import com.maodouchat.server.repository.GroupInvitationRepository
import com.maodouchat.server.repository.GroupLifecycleService
import com.maodouchat.server.repository.GroupMemberMutationResult
import com.maodouchat.server.repository.GroupModerationRepository
import com.maodouchat.server.repository.GroupProfileRepository
import com.maodouchat.server.repository.SenderKeyDistributionRepository
import com.maodouchat.server.repository.SignalKeyRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.FileStorageService
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** Authenticated group profile, role, moderation, audit and key-coverage adapter. */
internal fun Route.configureGroupAdministrationRoutes(
    userRepo: UserRepository,
    lifecycleService: GroupLifecycleService,
    profileRepository: GroupProfileRepository,
    moderationRepository: GroupModerationRepository,
    invitationRepository: GroupInvitationRepository,
    queryRepository: ConversationQueryRepository,
    participantRepository: ConversationParticipantRepository,
    auditRepository: GroupAuditRepository,
    signalKeyRepository: SignalKeyRepository,
    senderKeyRepository: SenderKeyDistributionRepository,
    avatarRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    authenticate("auth-jwt") {
        delete("/api/chats/{chatId}/members/{memberId}") {
            call.handleRemoveGroupMember(userRepo, lifecycleService, json)
        }

        put("/api/chats/{chatId}/name") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val chatId = call.parameters["chatId"].orEmpty()
            if (call.rejectIfSuspended(userRepo, userId)) return@put
            val name = call.receiveBoundedText()
                ?.let { parseJson<CreateChatRequest>(it) }
                ?.groupName
                .orEmpty()
                .trim()
            if (name.isEmpty() || name.length > 50) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("群名长度需 1-50 字符"))
                return@put
            }
            val result = profileRepository.updateName(chatId, userId, name)
            if (call.respondGroupMemberMutationFailure(result)) return@put
            notifyGroupRevisionChanged(queryRepository, participantRepository, json, chatId, "GROUP_RENAMED", userId)
            call.respondUpdatedGroup(queryRepository, chatId)
        }

        put("/api/chats/{chatId}/announcement") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val chatId = call.parameters["chatId"].orEmpty()
            if (call.rejectIfSuspended(userRepo, userId)) return@put
            val request = call.receiveBoundedText()?.let { parseJson<UpdateGroupAnnouncementRequest>(it) }
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@put
                }
            val announcement = request.announcement.trim().takeIf(String::isNotEmpty)
            if ((announcement?.length ?: 0) > 1200) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("群公告不能超过 1200 字符"))
                return@put
            }
            val result = profileRepository.updateAnnouncement(chatId, userId, announcement)
            if (call.respondGroupMemberMutationFailure(result)) return@put
            notifyGroupRevisionChanged(queryRepository, participantRepository, json, chatId, "ANNOUNCEMENT_UPDATED", userId)
            call.respondUpdatedGroup(queryRepository, chatId)
        }

        post("/api/chats/{chatId}/invite-token") {
            if (!RuntimeConfigService.isGroupInvitesEnabled()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("group_invites_disabled"))
                return@post
            }
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val chatId = call.parameters["chatId"].orEmpty()
            if (call.rejectIfSuspended(userRepo, userId)) return@post
            if (participantRepository.chatType(chatId) == ChatType.CHANNEL) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("频道不支持邀请链接"))
                return@post
            }
            val body = call.receiveBoundedText()
            val request = body?.takeIf(String::isNotBlank)?.let { parseJson<CreateGroupInviteRequest>(it) }
                ?: CreateGroupInviteRequest(rotate = call.request.queryParameters["rotate"] == "1")
            if (request.expiresInSeconds !in 300L..MAX_INVITE_TTL_SECONDS || request.maxUses !in 1..1000) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("邀请有效期或使用次数无效"))
                return@post
            }
            val result = invitationRepository.configureToken(
                chatId,
                userId,
                request.rotate,
                System.currentTimeMillis() + request.expiresInSeconds * 1000L,
                request.maxUses,
            )
            if (call.respondGroupMemberMutationFailure(result.result)) return@post
            val invite = result.invite ?: run {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("生成群邀请失败"))
                return@post
            }
            call.respond(
                GroupInviteResponse(
                    invite.token,
                    "maodouchat:chat-invite:v1:${invite.token}",
                    queryRepository.getById(chatId),
                    invite.expiresAt,
                    invite.maxUses,
                    invite.usedCount,
                    invite.remainingUses,
                ),
            )
        }

        post("/api/chats/{chatId}/avatar") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val chatId = call.parameters["chatId"].orEmpty()
            if (call.rejectIfSuspended(userRepo, userId)) return@post
            if (!avatarRateLimiter.acquire(userId, maxPerMinute = 10)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("头像操作过于频繁，请稍后再试"))
                return@post
            }
            if (!participantRepository.isOwnerOrAdmin(chatId, userId)) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    ErrorResponse("只有群主或管理员可以修改群头像", code = "GROUP_PERMISSION_DENIED"),
                )
                return@post
            }
            val request = call.receiveBoundedText(MAX_UPLOAD_JSON_BODY_CHARS)
                ?.let { parseJson<UploadAvatarRequest>(it) }
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("头像参数无效"))
                    return@post
                }
            val avatarUrl = try {
                FileStorageService.saveGroupAvatar(request.base64Data, chatId)
            } catch (error: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "群头像无效"))
                return@post
            }
            var committed = false
            try {
                val result = profileRepository.updateAvatar(chatId, userId, avatarUrl)
                if (result.result != GroupMemberMutationResult.UPDATED) {
                    call.respondGroupMemberMutationFailure(result.result)
                    return@post
                }
                committed = true
                FileStorageService.deleteGroupAvatarUrl(result.previousAvatarUrl, chatId)
                notifyGroupRevisionChanged(queryRepository, participantRepository, json, chatId, "AVATAR_UPDATED", userId)
                call.respond(buildJsonObject {
                    put("status", "ok")
                    put("avatarUrl", avatarUrl)
                })
            } finally {
                if (!committed) FileStorageService.deleteGroupAvatarUrl(avatarUrl, chatId)
            }
        }

        get("/api/chats/{chatId}/members") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val chatId = call.parameters["chatId"].orEmpty()
            if (!participantRepository.isParticipant(chatId, userId)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作"))
                return@get
            }
            val members = participantRepository.groupMembers(chatId, viewerId = userId)
            if (participantRepository.chatType(chatId) == ChatType.CHANNEL &&
                !participantRepository.isOwnerOrAdmin(chatId, userId)
            ) {
                call.respond(members.filter { it.role == "OWNER" || it.role == "ADMIN" })
            } else {
                call.respond(members)
            }
        }

        get("/api/chats/{chatId}/audit") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val chatId = call.parameters["chatId"].orEmpty()
            if (!participantRepository.isParticipant(chatId, userId) ||
                (participantRepository.chatType(chatId) == ChatType.CHANNEL &&
                    !participantRepository.isOwnerOrAdmin(chatId, userId))
            ) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权查看群操作记录"))
                return@get
            }
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 100)
            val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
            call.respond(auditRepository.list(chatId, limit, offset, viewerId = userId))
        }

        get("/api/chats/{chatId}/sender-key-distributions") {
            val principal = call.principal<JWTPrincipal>()!!
            val userId = principal.payload.subject
            val chatId = call.parameters["chatId"].orEmpty()
            if (!participantRepository.isParticipant(chatId, userId)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作"))
                return@get
            }
            if (participantRepository.chatType(chatId) == ChatType.CHANNEL &&
                !participantRepository.isOwnerOrAdmin(chatId, userId)
            ) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权查看密钥分发状态"))
                return@get
            }
            val requestedDeviceId = call.request.queryParameters["currentDeviceId"]?.toIntOrNull()
            if (requestedDeviceId != null && requestedDeviceId !in 1..255) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("设备参数无效"))
                return@get
            }
            val currentDeviceId = requestedDeviceId ?: authDeviceId(principal)
            val expectedTargets = signalKeyRepository
                .getConfirmedDeviceTargets(participantRepository.participantIds(chatId))
                .filterNot { (targetUserId, deviceId) ->
                    targetUserId.startsWith("bot_") ||
                        (targetUserId == userId && currentDeviceId != null && deviceId == currentDeviceId)
                }
                .toSet()
            val epoch = call.request.queryParameters["epoch"]?.toLongOrNull()
            call.respond(senderKeyRepository.getStatus(chatId, userId, epoch, expectedTargets))
        }

        put("/api/chats/{chatId}/members/{memberId}/role") {
            call.handleUpdateGroupMemberRole(userRepo, lifecycleService, json)
        }

        put("/api/chats/{chatId}/members/{memberId}/ownership") {
            call.handleTransferGroupOwnership(userRepo, lifecycleService, json)
        }

        put("/api/chats/{chatId}/members/me/nickname") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val chatId = call.parameters["chatId"].orEmpty()
            if (call.rejectIfSuspended(userRepo, userId)) return@put
            val request = call.receiveBoundedText()?.let { parseJson<UpdateGroupNicknameRequest>(it) }
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@put
                }
            val normalized = request.groupNickname.trim()
            if (normalized.length > 100) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("群昵称不能超过 100 字符"))
                return@put
            }
            val result = profileRepository.updateOwnNickname(chatId, userId, normalized.takeIf(String::isNotEmpty))
            if (call.respondGroupMemberMutationFailure(result)) return@put
            notifyGroupRevisionChanged(queryRepository, participantRepository, json, chatId, "NICKNAME_UPDATED", userId, userId)
            call.respondOk()
        }

        put("/api/chats/{chatId}/members/{memberId}/title") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val chatId = call.parameters["chatId"].orEmpty()
            val memberId = call.parameters["memberId"].orEmpty()
            if (call.rejectIfSuspended(userRepo, userId)) return@put
            val request = call.receiveBoundedText()?.let { parseJson<UpdateMemberTitleRequest>(it) }
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@put
                }
            val normalized = request.title.trim()
            if (normalized.length > 50) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("成员头衔不能超过 50 字符"))
                return@put
            }
            val result = profileRepository.updateMemberTitle(
                chatId,
                userId,
                memberId,
                normalized.takeIf(String::isNotEmpty),
            )
            if (call.respondGroupMemberMutationFailure(result)) return@put
            notifyGroupRevisionChanged(queryRepository, participantRepository, json, chatId, "TITLE_UPDATED", userId, memberId)
            call.respondOk()
        }

        put("/api/chats/{chatId}/members/{memberId}/mute") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val chatId = call.parameters["chatId"].orEmpty()
            val memberId = call.parameters["memberId"].orEmpty()
            if (call.rejectIfSuspended(userRepo, userId)) return@put
            val request = call.receiveBoundedText()?.let { parseJson<UpdateMemberMuteRequest>(it) }
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@put
                }
            val mutedUntil = call.normalizeMuteDeadline(request.mutedUntil) ?: return@put
            val result = moderationRepository.updateMemberMute(chatId, userId, memberId, mutedUntil)
            if (call.respondGroupMemberMutationFailure(result)) return@put
            notifyGroupRevisionChanged(queryRepository, participantRepository, json, chatId, "MUTE_UPDATED", userId, memberId)
            call.respond(buildJsonObject {
                put("status", "ok")
                put("mutedUntil", mutedUntil)
            })
        }

        post("/api/chats/{chatId}/mute-all") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val chatId = call.parameters["chatId"].orEmpty()
            if (call.rejectIfSuspended(userRepo, userId)) return@post
            if (!participantRepository.isOwnerOrAdmin(chatId, userId)) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    ErrorResponse("只有群主或管理员可以全员静音", code = "GROUP_PERMISSION_DENIED"),
                )
                return@post
            }
            val request = call.receiveBoundedText()?.let { parseJson<UpdateMemberMuteRequest>(it) }
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@post
                }
            val mutedUntil = call.normalizeMuteDeadline(request.mutedUntil) ?: return@post
            val result = moderationRepository.updateMembersMute(
                chatId,
                userId,
                participantRepository.participantIds(chatId),
                mutedUntil,
            )
            if (call.respondGroupMemberMutationFailure(result.result)) return@post
            if (result.updatedCount > 0) {
                notifyGroupRevisionChanged(queryRepository, participantRepository, json, chatId, "MUTE_UPDATED", userId)
            }
            call.respond(buildJsonObject {
                put("status", "ok")
                put("updated", result.updatedCount)
            })
        }
    }
}

private fun authDeviceId(principal: JWTPrincipal): Int? {
    val sessionId = JwtConfig.authSessionId(principal.payload)?.takeIf(String::isNotBlank) ?: return null
    return transaction {
        AuthSessions.selectAll()
            .where { AuthSessions.id eq sessionId }
            .firstOrNull()
            ?.get(AuthSessions.signalDeviceId)
    }?.takeIf { it in 1..255 }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondUpdatedGroup(
    queryRepository: ConversationQueryRepository,
    chatId: String,
) {
    val chat = queryRepository.getById(chatId)
    if (chat == null) respond(HttpStatusCode.InternalServerError, ErrorResponse("群聊状态异常，请刷新"))
    else respond(chat)
}

private suspend fun io.ktor.server.application.ApplicationCall.respondOk() {
    respond(buildJsonObject { put("status", "ok") })
}

private suspend fun io.ktor.server.application.ApplicationCall.normalizeMuteDeadline(requested: Long): Long? {
    val now = System.currentTimeMillis()
    if (requested > now + MAX_MUTE_DURATION_MS) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("禁言最长不能超过 30 天"))
        return null
    }
    return if (requested <= now) 0L else requested
}

private const val MAX_INVITE_TTL_SECONDS = 30L * 24L * 60L * 60L
