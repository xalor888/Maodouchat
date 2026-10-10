package com.maodouchat.server.plugins

import com.maodouchat.server.model.ChatType
import com.maodouchat.server.model.CreateChatRequest
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.GroupMemberMutationResult
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.FcmPushService
import com.maodouchat.server.service.GroupInvitationService
import com.maodouchat.server.service.GroupMembershipService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json

/** 群成员添加（频道直接加 / 群组走邀请）。 */
internal fun Route.configureGroupInvitationMemberRoutes(
    userRepo: UserRepository,
    membershipService: GroupMembershipService,
    invitationService: GroupInvitationService,
    queryRepository: ConversationQueryRepository,
    participantRepository: ConversationParticipantRepository,
    pushService: FcmPushService,
    json: Json,
) {
    post("/api/chats/{chatId}/members") {
        val userId = call.requireUserId()
        val chatId = call.requireNonBlankParamOr400("chatId", "聊天 ID 无效") ?: return@post
        if (call.rejectIfSuspended(userRepo, userId)) return@post
        val request = call.receiveJsonOr400<CreateChatRequest>() ?: return@post
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
            val addCommit = membershipService.addMembers(
                chatId,
                userId,
                requestedIds,
                MAX_CHANNEL_SUBSCRIBERS,
            )
            val result = addCommit.result
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
            val result = invitationService.inviteMembers(
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
            invitationService.listForChat(chatId)
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
}