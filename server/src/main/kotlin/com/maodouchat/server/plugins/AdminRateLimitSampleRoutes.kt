package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.RateLimitStatsRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 限流手动采样。 */
internal fun Route.configureAdminRateLimitSampleRoutes(
    rateLimitStatsRepo: RateLimitStatsRepository,
) {
    authenticate("admin-jwt") {
        route("/api/admin") {
                    post("/rate-limit/sample") {
                        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                        val actorId = call.requireUserId()
                        rateLimitStatsRepo.recordMinute()
                        recordAdminAudit(actorId, "RATE_LIMIT_MANUAL_SAMPLE", "")
                        call.respond(buildJsonObject { put("ok", true) })
                    }
        }
    }
}
