package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.SendSignalRequest
import com.maodouchat.server.model.SignalMessageResponse
import com.maodouchat.server.model.WsMessage
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.SignalingRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.CallInviteRateLimiter
import com.maodouchat.server.service.FcmPushService
import com.maodouchat.server.service.TurnCredentialService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Durable fallback and realtime wake-up adapter for WebRTC signaling. */
internal fun Route.configureCallSignalingRoutes(
    userRepository: UserRepository,
    conversationQueryRepository: ConversationQueryRepository,
    signalingRepository: SignalingRepository,
    callInviteRateLimiter: CallInviteRateLimiter,
    turnCredentialService: TurnCredentialService,
    pushService: FcmPushService,
    json: Json,
) {
    authenticate("auth-jwt") {
        get("/api/calls/ice-config") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            call.respond(turnCredentialService.issue(userId))
        }

        post("/api/signaling/send") {
            val fromUserId = call.principal<JWTPrincipal>()!!.payload.subject
            if (call.rejectIfMessageRestricted(userRepository, fromUserId)) return@post
            val request = call.receiveBoundedText()?.let { parseJson<SendSignalRequest>(it) } ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                return@post
            }
            if (!call.validateSignalRequest(request, fromUserId, userRepository, conversationQueryRepository)) return@post

            if (CallInviteRateLimiter.isInitialInvite(request.type, request.groupId, request.groupInvite)) {
                val key = CallInviteRateLimiter.sessionKey(request.callId, request.groupId, request.toUserId)
                val decision = callInviteRateLimiter.tryAcquire(fromUserId, key)
                if (!decision.allowed) {
                    call.response.headers.append(HttpHeaders.RetryAfter, decision.retryAfterSeconds.toString())
                    call.respond(
                        HttpStatusCode.TooManyRequests,
                        ErrorResponse(
                            "发起通话过于频繁，请稍后再试",
                            "CALL_INVITE_RATE_LIMITED",
                            decision.retryAfterSeconds,
                        ),
                    )
                    return@post
                }
            }

            val terminalTypes = setOf("hang-up", "busy", "reject")
            if (request.type.lowercase() in terminalTypes) {
                signalingRepository.storeTerminalAndClearOthers(
                    fromUserId,
                    request.toUserId,
                    request.type,
                    request.payload,
                    request.callId,
                    request.groupId,
                    request.groupMemberIds,
                    request.groupInvite,
                )
            } else {
                signalingRepository.store(
                    fromUserId,
                    request.toUserId,
                    request.type,
                    request.payload,
                    request.callId,
                    request.groupId,
                    request.groupMemberIds,
                    request.groupInvite,
                )
            }
            sendSignalWakeup(json, fromUserId, request)
            if (request.type.equals("offer", ignoreCase = true) &&
                (request.groupId.isBlank() || request.groupInvite)
            ) {
                pushService.enqueueIncomingCall(
                    recipientId = request.toUserId,
                    senderId = fromUserId,
                    isVideo = sdpHasActiveVideo(request.payload),
                    callId = request.callId,
                )
            }
            call.respond(buildJsonObject { put("status", "ok") })
        }

        get("/api/signaling/pending") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val offersOnly = call.request.queryParameters["offersOnly"]?.toBooleanStrictOrNull() == true
            call.respond(signalingRepository.consumeForUser(userId, offersOnly).map {
                SignalMessageResponse(
                    it.id,
                    it.fromUserId,
                    it.type,
                    it.payload,
                    it.timestamp,
                    it.callId,
                    it.groupId,
                    it.groupMemberIds,
                    it.groupInvite,
                )
            })
        }

        post("/api/signaling/hangup") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val request = call.receiveBoundedText()?.let { parseJson<SendSignalRequest>(it) } ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                return@post
            }
            if (!call.validateSignalRequest(request, userId, userRepository, conversationQueryRepository)) return@post
            signalingRepository.storeTerminalAndClearOthers(
                userId,
                request.toUserId,
                "hang-up",
                request.payload,
                request.callId,
                request.groupId,
                request.groupMemberIds,
                request.groupInvite,
            )
            sendSignalWakeup(json, userId, request.copy(type = "hang-up"))
            call.respond(buildJsonObject { put("status", "ok") })
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.validateSignalRequest(
    request: SendSignalRequest,
    fromUserId: String,
    userRepository: UserRepository,
    conversationQueryRepository: ConversationQueryRepository,
): Boolean {
    if (!isValidSignalPayload(request.type, request.payload) || !isValidCallId(request.callId)) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("信令内容无效"))
        return false
    }
    if (request.toUserId == fromUserId) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("不能向自己发送信令"))
        return false
    }
    if (userRepository.getById(request.toUserId) == null) {
        respond(HttpStatusCode.NotFound, ErrorResponse("信令目标用户不存在"))
        return false
    }
    if (!isValidGroupSignalMetadata(
            request.groupId,
            request.groupMemberIds,
            request.groupInvite,
            request.callId,
            fromUserId,
            request.toUserId,
            conversationQueryRepository,
        )
    ) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("群通话元数据无效"))
        return false
    }
    if (userRepository.isBlockedEitherWay(fromUserId, request.toUserId)) {
        respond(HttpStatusCode.Forbidden, ErrorResponse("无法与已屏蔽的用户发送通话信令"))
        return false
    }
    if (request.groupId.isBlank() && !conversationQueryRepository.shareConversation(fromUserId, request.toUserId)) {
        respond(HttpStatusCode.Forbidden, ErrorResponse("双方无共同会话，无法发送通话信令"))
        return false
    }
    return true
}

private suspend fun sendSignalWakeup(json: Json, fromUserId: String, request: SendSignalRequest) {
    sendToUser(
        request.toUserId,
        json.encodeToString(
            WsMessage.serializer(),
            WsMessage(
                "SIGNALING",
                json.encodeToString(
                    SignalingPayload.serializer(),
                    SignalingPayload(
                        fromUserId,
                        request.type,
                        request.payload,
                        request.callId,
                        request.groupId,
                        request.groupMemberIds,
                        request.groupInvite,
                    ),
                ),
            ),
        ),
    )
}

@Serializable
private data class SignalingPayload(
    val fromUserId: String,
    val type: String,
    val payload: String,
    val callId: String = "",
    val groupId: String = "",
    val groupMemberIds: List<String> = emptyList(),
    val groupInvite: Boolean = false,
)
