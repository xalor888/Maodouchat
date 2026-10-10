package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.GroupInviteAcceptResult
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.FcmPushService
import com.maodouchat.server.service.GroupInvitationService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 群邀请生命周期（列表/接受/拒绝/撤销）。 */
internal fun Route.configureGroupInvitationLifecycleRoutes(
    userRepo: UserRepository,
    invitationService: GroupInvitationService,
    queryRepository: ConversationQueryRepository,
    participantRepository: ConversationParticipantRepository,
    pushService: FcmPushService,
    json: Json,
) {
    get("/api/group-invitations") {
        val userId = call.requireUserId()
        call.respond(invitationService.listIncoming(userId))
    }
    get("/api/chats/{chatId}/invitations") {
        val userId = call.requireUserId()
        val chatId = parseRawOrEmpty(call.parameters, "chatId")
        if (!participantRepository.isParticipant(chatId, userId)) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("群聊不存在"))
            return@get
        }
        call.respond(invitationService.listForChat(chatId))
    }
    post("/api/group-invitations/{inviteId}/accept") {
        val userId = call.requireUserId()
        val inviteId = parseRawOrEmpty(call.parameters, "inviteId")
        if (call.rejectIfSuspended(userRepo, userId)) return@post
        val outcome = invitationService.accept(inviteId, userId, maxGroupMembers())
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
                invitationService.get(inviteId)?.let { invitation ->
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
        val userId = call.requireUserId()
        val inviteId = parseRawOrEmpty(call.parameters, "inviteId")
        if (!invitationService.decline(inviteId, userId)) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("邀请不存在或已处理"))
            return@post
        }
        invitationService.get(inviteId)?.let { invitation ->
            notifyGroupInvite(json, invitation, "DECLINED", pushService)
        }
        call.respond(buildJsonObject { put("status", "declined") })
    }
    delete("/api/group-invitations/{inviteId}") {
        val userId = call.requireUserId()
        val inviteId = parseRawOrEmpty(call.parameters, "inviteId")
        if (!invitationService.cancel(inviteId, userId)) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权撤销该邀请"))
            return@delete
        }
        invitationService.get(inviteId)?.let { invitation ->
            notifyGroupInvite(json, invitation, "CANCELLED", pushService)
        }
        call.respond(buildJsonObject { put("status", "cancelled") })
    }
}