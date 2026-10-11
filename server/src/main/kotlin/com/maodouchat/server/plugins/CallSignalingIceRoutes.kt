package com.maodouchat.server.plugins

import com.maodouchat.server.service.TurnCredentialService
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 通话信令·ICE：TURN 凭证下发。 */
internal fun Route.configureCallSignalingIceRoutes(
    turnCredentialService: TurnCredentialService,
) {
    authenticate("auth-jwt") {
        get("/api/calls/ice-config") {
            val userId = call.requireUserId()
            val callId = parseRawOrEmpty(call.request.queryParameters, "callId")
            call.respond(turnCredentialService.issue(userId, callId = callId))
        }
    }
}
