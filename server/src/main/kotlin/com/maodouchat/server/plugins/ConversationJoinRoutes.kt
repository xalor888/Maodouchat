package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.JoinGroupInviteRequest
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.GroupInvitationService
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json

private val INVITE_TOKEN_REGEX = Regex("^[A-Za-z0-9_-]{32,80}$")

/** 会话邀请加入：消费邀请 token。 */
internal fun Route.configureConversationJoinRoutes(
    userRepo: UserRepository,
    queryRepository: ConversationQueryRepository,
    invitationService: GroupInvitationService,
    json: Json,
) {
    authenticate("auth-jwt") {
        post("/api/chats/join-by-invite") {
            if (!RuntimeConfigService.isGroupInvitesEnabled()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("group_invites_disabled"))
                return@post
            }
            val userId = call.requireUserId()
            if (call.rejectIfSuspended(userRepo, userId)) return@post
            val request = call.receiveJsonOr400<JoinGroupInviteRequest>(message = "邀请参数无效") ?: return@post
            val token = request.token.trim()
            if (!INVITE_TOKEN_REGEX.matches(token)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("邀请二维码无效"))
                return@post
            }
            val consumed = invitationService.consumeToken(token, userId, maxGroupMembers()) ?: run {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("群邀请不存在或已失效"))
                return@post
            }
            when {
                consumed.channelRejected -> {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("频道不支持邀请加入"))
                    return@post
                }
                consumed.blocked -> {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse("无法加入含已屏蔽用户的群聊", code = "GROUP_INVITE_BLOCKED"),
                    )
                    return@post
                }
                consumed.limitExceeded -> {
                    call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse("群成员已达上限", code = "GROUP_MEMBER_LIMIT_EXCEEDED"),
                    )
                    return@post
                }
            }
            if (consumed.newlyJoined) {
                notifyGroupRevisionChangedWithData(
                    json = json,
                    chatId = consumed.chatId,
                    reason = "MEMBER_ADDED",
                    actorId = userId,
                    targetUserId = userId,
                    memberRevision = consumed.memberRevisionAfter ?: 0L,
                    recipientIds = consumed.recipientsAfter,
                )
            }
            val chat = queryRepository.getForParticipant(consumed.chatId, userId) ?: run {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("群聊状态异常，请刷新"))
                return@post
            }
            call.respond(chat)
        }
    }
}
