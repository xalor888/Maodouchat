package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.SendSignalRequest
import com.maodouchat.server.model.SignalMessageResponse
import com.maodouchat.server.model.WsMessage
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.CallSignalingService
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
    callSignalingService: CallSignalingService,
    turnCredentialService: TurnCredentialService,
    pushService: FcmPushService,
    json: Json,
) {
    authenticate("auth-jwt") {
        get("/api/calls/ice-config") {
            val userId = call.requireUserId()
            call.respond(turnCredentialService.issue(userId))
        }

        post("/api/signaling/send") {
            val fromUserId = call.requireUserId()
            if (call.rejectIfMessageRestricted(userRepository, fromUserId)) return@post
            val request = call.receiveJson<SendSignalRequest>() ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                return@post
            }
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

        get("/api/signaling/pending") {
            val userId = call.requireUserId()
            val offersOnly = call.request.queryParameters["offersOnly"]?.toBooleanStrictOrNull() == true
            call.respond(callSignalingService.pending(userId, offersOnly).map {
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
                    it.epoch,
                    it.sequence,
                    it.idempotencyKey,
                )
            })
        }

        post("/api/signaling/hangup") {
            val userId = call.requireUserId()
            val request = call.receiveJson<SendSignalRequest>() ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                return@post
            }
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
