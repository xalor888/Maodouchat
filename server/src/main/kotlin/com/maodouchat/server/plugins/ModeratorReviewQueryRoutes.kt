package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.ReportWorkflow
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.getReports
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 审核员：举报列表查询。 */
internal fun Route.configureModeratorReviewQueryRoutes(
    userRepo: UserRepository,
    reportRepo: ReportWorkflow,
) {
    authenticate("auth-jwt") {
        get("/api/moderator/reports") {
            val uid = call.requireUserId()
            if (!hasContentModerationAccess(userRepo, uid)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要审核员权限"))
                return@get
            }
            val status = parseRawOrNull(call.request.queryParameters, "status")
            val limit = parseAdminListLimit(call.request.queryParameters, defaultLimit = 100)
            val offset = parseAdminListOffset(call.request.queryParameters)
            call.respond(reportRepo.getReports(status, limit, offset))
        }
    }
}
