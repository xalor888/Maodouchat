package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*

/** 用户举报：提交举报、我的举报。 */
internal fun Route.configureReportUserReportRoutes(
    reportRepo: ReportWorkflow,
    reportRateLimiter: BoundedRateLimiter,
) {
    authenticate("auth-jwt") {

        post("/api/reports") {

            if (!RuntimeConfigService.isBlockReportEnabled()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("block_report_disabled"))
                return@post
            }
            val uid = call.requireUserId()
            if (!reportRateLimiter.acquire(uid, maxPerMinute = 5)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("举报过于频繁，请稍后再试"))
                return@post
            }
            val req = call.receiveJsonOr400<CreateReportRequest>() ?: return@post
            when (val result = reportRepo.createReport(uid, req)) {
                is ReportWorkflow.CreateResult.Success -> call.respond(HttpStatusCode.Created, result.report)
                is ReportWorkflow.CreateResult.Failure -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
            }
        }

        get("/api/reports/mine") {
            val uid = call.requireUserId()
            val limit = parseAdminListLimit(call.request.queryParameters, maxLimit = 100)
            call.respond(reportRepo.getMyReports(uid, limit))
        }
    }
}
