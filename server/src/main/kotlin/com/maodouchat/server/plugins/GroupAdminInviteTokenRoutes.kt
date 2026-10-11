package com.maodouchat.server.plugins

import com.maodouchat.server.model.ChatType
import com.maodouchat.server.model.CreateGroupInviteRequest
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.GroupInviteResponse
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.GroupInvitationService
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/** 群管理路由：群邀请链接（生成/轮换）。 */
internal fun Route.configureGroupAdminInviteTokenRoutes(
    userRepo: UserRepository,
    invitationService: GroupInvitationService,
    queryRepository: ConversationQueryRepository,
    participantRepository: ConversationParticipantRepository,
) {
    authenticate("auth-jwt") {

        post("/api/chats/{chatId}/invite-token") {
            if (!RuntimeConfigService.isGroupInvitesEnabled()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("group_invites_disabled"))
                return@post
            }
            val userId = call.requireUserId()
            val chatId = parseRawOrEmpty(call.parameters, "chatId")
            if (call.rejectIfSuspended(userRepo, userId)) return@post
            if (participantRepository.chatType(chatId) == ChatType.CHANNEL) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("频道不支持邀请链接"))
                return@post
            }
            val body = call.receiveBoundedText()
            val request = body?.takeIf(String::isNotBlank)?.let { parseJson<CreateGroupInviteRequest>(it) }
                ?: CreateGroupInviteRequest(rotate = parseQueryFlagOne(call.request.queryParameters, "rotate"))
            if (request.expiresInSeconds !in 300L..MAX_INVITE_TTL_SECONDS || request.maxUses !in 1..1000) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("邀请有效期或使用次数无效"))
                return@post
            }
            val result = invitationService.configureToken(
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


    }
}


private const val MAX_INVITE_TTL_SECONDS = 30L * 24L * 60L * 60L
