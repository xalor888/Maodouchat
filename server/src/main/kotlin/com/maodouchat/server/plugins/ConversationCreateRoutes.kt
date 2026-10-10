package com.maodouchat.server.plugins

import com.maodouchat.server.model.ChatType
import com.maodouchat.server.model.CreateChatRequest
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.ConversationCommandService
import com.maodouchat.server.service.CreateConversationCommand
import com.maodouchat.server.service.CreateConversationOutcome
import com.maodouchat.server.service.CreateConversationResult
import com.maodouchat.server.service.FcmPushService
import com.maodouchat.server.service.GroupInvitationService
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json

/** 会话创建：私聊 / 群聊 / 频道 / 密聊。 */
internal fun Route.configureConversationCreateRoutes(
    userRepo: UserRepository,
    commandService: ConversationCommandService,
    queryRepository: ConversationQueryRepository,
    invitationService: GroupInvitationService,
    pushService: FcmPushService,
    createRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    authenticate("auth-jwt") {
        post("/api/chats") {
            val userId = call.requireUserId()
            if (!createRateLimiter.acquire(userId, maxPerMinute = 20)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("创建会话过于频繁，请稍后再试"))
                return@post
            }
            if (call.rejectIfSuspended(userRepo, userId)) return@post
            val request = call.receiveJsonOr400<CreateChatRequest>() ?: return@post
            val requestedType = request.chatType?.trim()?.takeIf(String::isNotEmpty)
                ?: if (request.isGroup) ChatType.GROUP else ChatType.DIRECT
            if (requestedType == ChatType.SECRET && !RuntimeConfigService.isSecretChatEnabled()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("secret_chat_disabled"))
                return@post
            }
            if (requestedType == ChatType.CHANNEL && !RuntimeConfigService.isChannelsEnabled()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("channels_disabled"))
                return@post
            }
            val outcome = commandService.create(
                actorId = userId,
                command = CreateConversationCommand(
                    participantIds = request.participantIds,
                    isGroup = request.isGroup,
                    groupName = request.groupName,
                    chatType = request.chatType,
                ),
                maxGroupMembers = maxGroupMembers(),
                maxChannelMembers = MAX_CHANNEL_SUBSCRIBERS,
            )
            if (outcome.result != CreateConversationResult.CREATED) {
                call.respondCreationFailure(outcome)
                return@post
            }
            val conversationId = outcome.conversationId ?: run {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("会话创建状态异常，请重试"))
                return@post
            }
            if (outcome.invitedUserIds.isNotEmpty()) {
                val invitedIds = outcome.invitedUserIds.toSet()
                invitationService.listForChat(conversationId)
                    .filter { it.userId in invitedIds }
                    .forEach { invitation ->
                        notifyGroupInvite(json, invitation, "CREATED", pushService)
                    }
            }
            val response = queryRepository.getById(conversationId, userId) ?: run {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("会话创建成功但读取失败，请刷新"))
                return@post
            }
            call.respond(HttpStatusCode.Created, response)
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondCreationFailure(
    outcome: CreateConversationOutcome,
) {
    val type = outcome.chatType
    when (outcome.result) {
        CreateConversationResult.INVALID_TYPE ->
            respond(HttpStatusCode.BadRequest, ErrorResponse("会话类型无效"))
        CreateConversationResult.INVALID_TYPE_SHAPE ->
            respond(HttpStatusCode.BadRequest, ErrorResponse("会话类型与群聊标记冲突"))
        CreateConversationResult.INVALID_PARTICIPANT ->
            respond(HttpStatusCode.BadRequest, ErrorResponse("参与者 ID 无效"))
        CreateConversationResult.DUPLICATE_PARTICIPANT ->
            respond(HttpStatusCode.BadRequest, ErrorResponse("参与者不能重复"))
        CreateConversationResult.EMPTY_PARTICIPANTS ->
            respond(HttpStatusCode.BadRequest, ErrorResponse("参与者不能为空"))
        CreateConversationResult.SELF_DIRECT ->
            respond(HttpStatusCode.BadRequest, ErrorResponse("不能创建仅包含自己的私聊"))
        CreateConversationResult.DIRECT_MEMBER_COUNT -> respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(if (type == ChatType.SECRET) "密聊必须且只能包含 2 名用户" else "私聊必须且只能包含 2 名用户"),
        )
        CreateConversationResult.GROUP_NAME_TOO_LONG ->
            respond(HttpStatusCode.BadRequest, ErrorResponse("群名不能超过 50 字符"))
        CreateConversationResult.MEMBER_LIMIT_EXCEEDED -> {
            val limit = if (type == ChatType.CHANNEL) MAX_CHANNEL_SUBSCRIBERS else maxGroupMembers()
            respond(HttpStatusCode.BadRequest, ErrorResponse("成员不能超过 $limit 人"))
        }
        CreateConversationResult.PARTICIPANT_NOT_FOUND ->
            respond(HttpStatusCode.NotFound, ErrorResponse("参与者不存在: ${outcome.missingUserId.orEmpty()}"))
        CreateConversationResult.PARTICIPANT_BLOCKED -> {
            val isChannel = type == ChatType.CHANNEL
            val isGroup = type == ChatType.GROUP
            val message = when {
                isChannel -> "无法与已屏蔽的用户创建频道"
                isGroup -> "无法与已屏蔽的用户创建群聊"
                type == ChatType.SECRET -> "无法与已屏蔽的用户创建密聊"
                else -> "无法与已屏蔽的用户创建私聊"
            }
            val code = when {
                isChannel -> "CHANNEL_CREATE_BLOCKED"
                isGroup -> "GROUP_CREATE_BLOCKED"
                else -> null
            }
            respond(HttpStatusCode.Forbidden, ErrorResponse(message, code = code))
        }
        CreateConversationResult.INVITATION_FAILED ->
            respond(HttpStatusCode.Conflict, ErrorResponse("群邀请创建失败，请重试"))
        CreateConversationResult.CREATED ->
            respond(HttpStatusCode.InternalServerError, ErrorResponse("会话创建状态异常，请重试"))
    }
}
