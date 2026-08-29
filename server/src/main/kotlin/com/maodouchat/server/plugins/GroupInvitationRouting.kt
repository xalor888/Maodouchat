package com.maodouchat.server.plugins

import com.maodouchat.server.model.ChatType
import com.maodouchat.server.model.CreateChatRequest
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.GroupInvitationRepository
import com.maodouchat.server.repository.GroupInviteAcceptResult
import com.maodouchat.server.repository.GroupMemberMutationResult
import com.maodouchat.server.repository.GroupMembershipRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.FcmPushService
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** HTTP adapter for channel member addition and durable group invitation approval. */
internal fun Route.configureGroupInvitationRoutes(
    userRepo: UserRepository,
    membershipRepository: GroupMembershipRepository,
    invitationRepository: GroupInvitationRepository,
    queryRepository: ConversationQueryRepository,
    participantRepository: ConversationParticipantRepository,
    pushService: FcmPushService,
    json: Json,
) {
    authenticate("auth-jwt") {
        post("/api/chats/{chatId}/members") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val chatId = call.parameters["chatId"]?.takeIf(String::isNotBlank) ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("聊天 ID 无效"))
                return@post
            }
            if (call.rejectIfSuspended(userRepo, userId)) return@post
            val request = call.receiveBoundedText()?.let { parseJson<CreateChatRequest>(it) } ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                return@post
            }
            val requestedIds = request.participantIds.map(String::trim)
            if (requestedIds.isEmpty() || requestedIds.any(String::isBlank) ||
                requestedIds.distinct().size != requestedIds.size
            ) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("成员不能为空或重复"))
                return@post
            }
            val chatType = participantRepository.chatType(chatId)
            val memberCap = if (chatType == ChatType.CHANNEL) MAX_CHANNEL_SUBSCRIBERS else maxGroupMembers()
            if (requestedIds.size > memberCap) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("一次性添加成员不能超过 $memberCap 人"))
                return@post
            }
            if (chatType == ChatType.CHANNEL) {
                val result = membershipRepository.addMembers(
                    chatId,
                    userId,
                    requestedIds,
                    MAX_CHANNEL_SUBSCRIBERS,
                )
                when (result.result) {
                    GroupMemberMutationResult.USER_NOT_FOUND -> {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ErrorResponse(
                                "用户不存在: ${result.missingUserId.orEmpty()}",
                                code = "GROUP_USER_NOT_FOUND",
                            ),
                        )
                        return@post
                    }
                    GroupMemberMutationResult.BLOCKED -> {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            ErrorResponse(
                                "无法添加已屏蔽的用户: ${result.blockedUserId.orEmpty()}",
                                code = "GROUP_MEMBER_BLOCKED",
                            ),
                        )
                        return@post
                    }
                    else -> if (call.respondGroupMemberMutationFailure(result.result)) return@post
                }
                if (result.addedUserIds.isNotEmpty()) {
                    notifyGroupRevisionChanged(
                        queryRepository,
                        participantRepository,
                        json,
                        chatId,
                        "MEMBER_ADDED",
                        userId,
                        result.addedUserIds.firstOrNull(),
                    )
                }
            } else {
                val result = invitationRepository.inviteMembers(
                    chatId,
                    userId,
                    requestedIds,
                    maxGroupMembers(),
                )
                when (result.result) {
                    GroupMemberMutationResult.USER_NOT_FOUND -> {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ErrorResponse(
                                "用户不存在: ${result.missingUserId.orEmpty()}",
                                code = "GROUP_USER_NOT_FOUND",
                            ),
                        )
                        return@post
                    }
                    GroupMemberMutationResult.BLOCKED -> {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            ErrorResponse("无法邀请已屏蔽的用户", code = "GROUP_MEMBER_BLOCKED"),
                        )
                        return@post
                    }
                    else -> if (call.respondGroupMemberMutationFailure(result.result)) return@post
                }
                val invitedIds = result.invitedUserIds.toSet()
                invitationRepository.listForChat(chatId)
                    .filter { it.userId in invitedIds }
                    .forEach { invitation ->
                        notifyGroupInvite(json, invitation, "CREATED", pushService)
                    }
            }
            val updated = queryRepository.getById(chatId) ?: run {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("群聊状态异常，请刷新"))
                return@post
            }
            call.respond(updated)
        }

        get("/api/group-invitations") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            call.respond(invitationRepository.listIncoming(userId))
        }

        get("/api/chats/{chatId}/invitations") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val chatId = call.parameters["chatId"].orEmpty()
            if (!participantRepository.isParticipant(chatId, userId)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("群聊不存在"))
                return@get
            }
            call.respond(invitationRepository.listForChat(chatId))
        }

        post("/api/group-invitations/{inviteId}/accept") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val inviteId = call.parameters["inviteId"].orEmpty()
            if (call.rejectIfSuspended(userRepo, userId)) return@post
            val outcome = invitationRepository.accept(inviteId, userId, maxGroupMembers())
            val chat = outcome.chatId?.let { queryRepository.getById(it, userId) }
            when (outcome.result) {
                GroupInviteAcceptResult.ACCEPTED -> {
                    if (outcome.chatId != null && outcome.memberRevisionAfter != null) {
                        notifyGroupRevisionChangedWithData(
                            json = json,
                            chatId = outcome.chatId,
                            reason = "MEMBER_ADDED",
                            actorId = userId,
                            targetUserId = userId,
                            memberRevision = outcome.memberRevisionAfter,
                            recipientIds = outcome.recipientsAfter,
                        )
                    }
                    invitationRepository.get(inviteId)?.let { invitation ->
                        notifyGroupInvite(json, invitation, "ACCEPTED", pushService)
                    }
                    call.respond(buildJsonObject {
                        put("status", "accepted")
                        put("chatId", chat?.id.orEmpty())
                    })
                }
                GroupInviteAcceptResult.ALREADY_MEMBER -> call.respond(buildJsonObject {
                    put("status", "already_member")
                    put("chatId", chat?.id.orEmpty())
                })
                GroupInviteAcceptResult.NOT_FOUND ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("邀请不存在"))
                GroupInviteAcceptResult.NOT_PENDING ->
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("邀请已处理"))
                GroupInviteAcceptResult.NOT_INVITEE ->
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("该邀请不属于你"))
                GroupInviteAcceptResult.CHAT_NOT_FOUND,
                GroupInviteAcceptResult.NOT_GROUP,
                GroupInviteAcceptResult.CHANNEL_NOT_SUPPORTED ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("群聊不存在"))
                GroupInviteAcceptResult.MEMBER_LIMIT_EXCEEDED -> call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("群成员数量已达上限", code = "GROUP_MEMBER_LIMIT_EXCEEDED"),
                )
                GroupInviteAcceptResult.BLOCKED -> call.respond(
                    HttpStatusCode.Forbidden,
                    ErrorResponse("无法加入含已屏蔽用户的群聊", code = "GROUP_INVITE_BLOCKED"),
                )
                GroupInviteAcceptResult.USER_DEACTIVATED ->
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("账号状态异常"))
            }
        }

        post("/api/group-invitations/{inviteId}/decline") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val inviteId = call.parameters["inviteId"].orEmpty()
            if (!invitationRepository.decline(inviteId, userId)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("邀请不存在或已处理"))
                return@post
            }
            invitationRepository.get(inviteId)?.let { invitation ->
                notifyGroupInvite(json, invitation, "DECLINED", pushService)
            }
            call.respond(buildJsonObject { put("status", "declined") })
        }

        delete("/api/group-invitations/{inviteId}") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val inviteId = call.parameters["inviteId"].orEmpty()
            if (!invitationRepository.cancel(inviteId, userId)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权撤销该邀请"))
                return@delete
            }
            invitationRepository.get(inviteId)?.let { invitation ->
                notifyGroupInvite(json, invitation, "CANCELLED", pushService)
            }
            call.respond(buildJsonObject { put("status", "cancelled") })
        }
    }
}
