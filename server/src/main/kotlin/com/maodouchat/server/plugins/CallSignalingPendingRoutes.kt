package com.maodouchat.server.plugins

import com.maodouchat.server.model.SignalMessageResponse
import com.maodouchat.server.service.CallSignalingService
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 通话信令·待取：离线期间积压的信令拉取。 */
internal fun Route.configureCallSignalingPendingRoutes(
    callSignalingService: CallSignalingService,
) {
    authenticate("auth-jwt") {
        get("/api/signaling/pending") {
            val userId = call.requireUserId()
            val offersOnly = parseStrictBooleanFlag(call.request.queryParameters, "offersOnly")
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
    }
}
