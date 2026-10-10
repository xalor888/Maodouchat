package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun Route.configureAdminRiskEventRoutes(
    adminManagementRepo: com.maodouchat.server.repository.AdminManagementRepository = com.maodouchat.server.repository.AdminManagementRepository(),
) {
// 风控事件监控
    get("/risk-events") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val limit = parseAdminListLimit(call.request.queryParameters)
        val offset = parseAdminListOffset(call.request.queryParameters)
        val needsReviewOnly = parseQueryFlag(call.request.queryParameters, "pending")
        val events = adminManagementRepo.listRiskEvents(limit, offset, needsReviewOnly)
        call.respond(events)
    }

    put("/risk-events/{id}/resolve") {
        if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val actorId = call.requireUserId()
        val id = call.requirePathParamOr400("id", "缺少事件 ID") ?: return@put
        val updated = adminManagementRepo.resolveRiskEvent(id)
        if (!updated) return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("事件不存在"))
        recordAdminAudit(actorId, "RISK_EVENT_RESOLVED", "eventId=$id")
        call.respond(
            buildJsonObject {
                put("status", "resolved")
            }
        )
    }
}
