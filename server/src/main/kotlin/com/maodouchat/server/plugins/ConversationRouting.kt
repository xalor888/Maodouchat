package com.maodouchat.server.plugins

import com.maodouchat.server.model.ChatType
import com.maodouchat.server.model.CreateChatRequest
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.JoinGroupInviteRequest
import com.maodouchat.server.repository.ConversationCreationService
import com.maodouchat.server.repository.ConversationLifecycleRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.CreateConversationCommand
import com.maodouchat.server.repository.CreateConversationOutcome
import com.maodouchat.server.repository.CreateConversationResult
import com.maodouchat.server.repository.GroupInvitationRepository
import com.maodouchat.server.repository.LeaveConversationResult
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.EncryptedAttachmentStorage
import com.maodouchat.server.service.FcmPushService
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Core conversation lifecycle HTTP adapter. */
internal fun Route.configureConversationRoutes(
    userRepo: UserRepository,
    creationService: ConversationCreationService,
    queryRepository: ConversationQueryRepository,
    invitationRepository: GroupInvitationRepository,
    lifecycleRepository: ConversationLifecycleRepository,
    pushService: FcmPushService,
    createRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    authenticate("auth-jwt") {
        get("/api/chats") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            call.respond(queryRepository.listForUser(userId))
        }

        post("/api/chats") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            if (!createRateLimiter.acquire(userId, maxPerMinute = 20)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("创建会话过于频繁，请稍后再试"))
                return@post
            }
            if (call.rejectIfSuspended(userRepo, userId)) return@post
            val request = call.receiveBoundedText()?.let { parseJson<CreateChatRequest>(it) } ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                return@post
            }
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
            val outcome = creationService.create(
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
                invitationRepository.listForChat(conversationId)
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

        post("/api/chats/join-by-invite") {
            if (!RuntimeConfigService.isGroupInvitesEnabled()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("group_invites_disabled"))
                return@post
            }
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            if (call.rejectIfSuspended(userRepo, userId)) return@post
            val request = call.receiveBoundedText()?.let { parseJson<JoinGroupInviteRequest>(it) } ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("邀请参数无效"))
                return@post
            }
            val token = request.token.trim()
            if (!INVITE_TOKEN_REGEX.matches(token)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("邀请二维码无效"))
                return@post
            }
            val consumed = invitationRepository.consumeToken(token, userId, maxGroupMembers()) ?: run {
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

        get("/api/chats/{id}") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val chatId = call.parameters["id"]?.takeIf(String::isNotBlank) ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("聊天 ID 无效"))
                return@get
            }
            val chat = queryRepository.getForParticipant(chatId, userId) ?: run {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("聊天不存在"))
                return@get
            }
            call.respond(chat)
        }

        delete("/api/chats/{id}") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val chatId = call.parameters["id"]?.takeIf(String::isNotBlank) ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("聊天 ID 无效"))
                return@delete
            }
            val outcome = lifecycleRepository.leave(chatId, userId)
            when (outcome.result) {
                LeaveConversationResult.OWNER_TRANSFER_REQUIRED -> {
                    call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse("群主需先转让群主身份再退出群聊", code = "GROUP_OWNER_TRANSFER_REQUIRED"),
                    )
                    return@delete
                }
                LeaveConversationResult.NOT_PARTICIPANT -> {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作该聊天"))
                    return@delete
                }
                LeaveConversationResult.LEFT -> {
                    outcome.deletedAttachmentIds.forEach(EncryptedAttachmentStorage::delete)
                    FileStorageService.deleteGroupAvatarUrl(outcome.deletedGroupAvatarUrl, chatId)
                }
            }
            if (outcome.wasGroup && outcome.memberRevisionAfter != null) {
                notifyGroupRevisionChangedWithData(
                    json = json,
                    chatId = chatId,
                    reason = "MEMBER_LEFT",
                    actorId = userId,
                    targetUserId = userId,
                    memberRevision = outcome.memberRevisionAfter,
                    recipientIds = outcome.recipientsBefore,
                )
            }
            call.respond(buildJsonObject { put("status", "ok") })
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

private val INVITE_TOKEN_REGEX = Regex("^[A-Za-z0-9_-]{32,80}$")
