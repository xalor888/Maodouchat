package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.AiGateway
import com.maodouchat.server.service.FcmPushService
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

// 星标与置顶（消息星标、会话置顶、星标列表）。
internal fun Route.configureSocialPostStarPinRoutes(
    userRepo: UserRepository,
    postRepo: PostRepository,
    moderationRuleRepo: ModerationRuleRepository,
    aiGateway: AiGateway,
    pushService: FcmPushService,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    starMessageRepo: StarMessageRepository,
    pinnedMessageRepo: PinnedMessageRepository,
    postRateLimiter: BoundedRateLimiter,
    postImageRateLimiter: BoundedRateLimiter,
    commentRateLimiter: BoundedRateLimiter,
    postLikeRateLimiter: BoundedRateLimiter,
    commentLikeRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    authenticate("auth-jwt") {
            post("/api/messages/{messageId}/star") {
                val uid = call.requireUserId()
                if (!com.maodouchat.server.service.RuntimeConfigService.isMessageStarringEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("starring_disabled"))
                    return@post
                }
                val mid = call.requirePathParamOr400("messageId", "缺少消息 ID") ?: return@post
                val starred = starMessageRepo.toggleStar(uid, mid)
                if (starred == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("该消息不能星标"))
                    return@post
                }
                call.respond(buildJsonObject { put("status", "ok"); put("starred", starred) })
            }

            // 会话消息置顶（群：管理员；单聊：双方）

            get("/api/chats/{chatId}/pins") {
                val uid = call.requireUserId()
                val chatId = call.requirePathParamOr400("chatId", "缺少聊天 ID") ?: return@get
                if (!conversationParticipantRepo.isParticipant(chatId, uid)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作"))
                    return@get
                }
                call.respond(
                    PinnedMessagesListResponse(
                        chatId = chatId,
                        pins = pinnedMessageRepo.list(chatId)
                    )
                )
            }

            post("/api/chats/{chatId}/messages/{messageId}/pin") {
                if (call.rejectIfMaintenance()) return@post
                if (!RuntimeConfigService.isMessagePinEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("message_pin_disabled"))
                    return@post
                }
                val uid = call.requireUserId()
                if (call.rejectIfSuspended(userRepo, uid)) return@post
                val chatId = call.requirePathParamOr400("chatId", "缺少聊天 ID") ?: return@post
                val mid = call.requirePathParamOr400("messageId", "缺少消息 ID") ?: return@post
                if (!conversationParticipantRepo.isParticipant(chatId, uid)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作"))
                    return@post
                }
                val chat = conversationQueryRepo.getById(chatId)
                if (chat == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("聊天不存在"))
                    return@post
                }
                val actorIsManager = if (chat.isGroup) conversationParticipantRepo.isOwnerOrAdmin(chatId, uid) else true
                val outcome = pinnedMessageRepo.toggle(
                    chatId = chatId,
                    messageId = mid,
                    actorId = uid,
                    actorIsManager = actorIsManager
                )
                when (outcome.result) {
                    PinnedMessageRepository.PinResult.NOT_FOUND -> {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("消息不存在"))
                        return@post
                    }
                    PinnedMessageRepository.PinResult.FORBIDDEN -> {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("仅群主或管理员可置顶"))
                        return@post
                    }
                    PinnedMessageRepository.PinResult.LIMIT -> {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("最多置顶 ${PinnedMessageRepository.MAX_PINS_PER_CHAT} 条消息")
                        )
                        return@post
                    }
                    PinnedMessageRepository.PinResult.NOT_PINNABLE -> {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("该消息不能置顶"))
                        return@post
                    }
                    PinnedMessageRepository.PinResult.PINNED,
                    PinnedMessageRepository.PinResult.UNPINNED -> {
                        val pinned = outcome.result == PinnedMessageRepository.PinResult.PINNED
                        val payload = PinnedMessagesUpdatedPayload(chatId, uid, outcome.pins)
                        val pinJson = json.encodeToString(
                            WsMessage.serializer(),
                            WsMessage(
                                "PINNED_MESSAGES_UPDATED",
                                json.encodeToString(PinnedMessagesUpdatedPayload.serializer(), payload)
                            )
                        )
                        conversationParticipantRepo.participantIds(chatId).forEach { participantId ->
                            LocalRealtimeBus.publish(participantId, pinJson)
                        }
                        call.respond(
                            TogglePinResponse(
                                status = "ok",
                                pinned = pinned,
                                pins = outcome.pins
                            )
                        )
                    }
                }
            }

            get("/api/messages/starred") {
                val uid = call.requireUserId()
                if (!com.maodouchat.server.service.RuntimeConfigService.isMessageStarringEnabled()) {
                    call.respond(emptyList<com.maodouchat.server.model.StarredMessageReference>())
                    return@get
                }
                val chatId = parseRawOrNull(call.request.queryParameters, "chatId")
                call.respond(starMessageRepo.getStarredMessages(uid, chatId))
            }
    }
}
