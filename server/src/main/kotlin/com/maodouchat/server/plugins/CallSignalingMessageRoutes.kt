package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.SendSignalRequest
import com.maodouchat.server.model.WsMessage
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.CallSignalingService
import com.maodouchat.server.service.FcmPushService
import com.maodouchat.server.service.enqueueIncomingCall
import com.maodouchat.server.service.hangUp
import com.maodouchat.server.service.send
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 通话信令·消息：offer/answer 等信令发送与挂断。 */
internal fun Route.configureCallSignalingMessageRoutes(
    userRepository: UserRepository,
    callSignalingService: CallSignalingService,
    pushService: FcmPushService,
    json: Json,
) {
    authenticate("auth-jwt") {
        post("/api/signaling/send") {
            val fromUserId = call.requireUserId()
            if (call.rejectIfMessageRestricted(userRepository, fromUserId)) return@post
            val request = call.receiveJsonOr400<SendSignalRequest>() ?: return@post
            when (val outcome = callSignalingService.send(request, fromUserId)) {
                is CallSignalingService.SendOutcome.Rejected -> {
                    outcome.retryAfterSeconds?.let { call.response.headers.append(HttpHeaders.RetryAfter, it.toString()) }
                    call.respond(outcome.status, ErrorResponse(outcome.message, outcome.code, outcome.retryAfterSeconds))
                }
                is CallSignalingService.SendOutcome.Stored -> {
                    val stored = outcome.request
                    sendSignalWakeup(json, fromUserId, stored)
                    if (stored.type.equals("offer", ignoreCase = true) &&
                        (stored.groupId.isBlank() || stored.groupInvite)
                    ) {
                        pushService.enqueueIncomingCall(
                            recipientId = stored.toUserId,
                            senderId = fromUserId,
                            isVideo = sdpHasActiveVideo(stored.payload),
                            callId = stored.callId,
                        )
                    }
                    call.respond(buildJsonObject { put("status", "ok") })
                }
            }
        }

        post("/api/signaling/hangup") {
            val userId = call.requireUserId()
            val request = call.receiveJsonOr400<SendSignalRequest>() ?: return@post
            when (val outcome = callSignalingService.hangUp(request, userId)) {
                is CallSignalingService.SendOutcome.Rejected ->
                    call.respond(outcome.status, ErrorResponse(outcome.message, outcome.code, outcome.retryAfterSeconds))
                is CallSignalingService.SendOutcome.Stored -> {
                    sendSignalWakeup(json, userId, outcome.request)
                    call.respond(buildJsonObject { put("status", "ok") })
                }
            }
        }
    }
}

private suspend fun sendSignalWakeup(json: Json, fromUserId: String, request: SendSignalRequest) {
    LocalRealtimeBus.publish(
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
                        request.epoch,
                        request.sequence,
                        request.idempotencyKey,
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
    val epoch: Long = 0,
    val sequence: Long = 0,
    val idempotencyKey: String = "",
)
