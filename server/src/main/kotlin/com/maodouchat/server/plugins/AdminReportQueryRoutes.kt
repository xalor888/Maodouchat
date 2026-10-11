package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.ReportWorkflow
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 举报查询。 */
internal fun Route.configureAdminReportQueryRoutes(
    reportRepo: ReportWorkflow,
) {
// 举报管理（admin-jwt 代理）
get("/reports") {
    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
    val limit = parseAdminListLimit(call.request.queryParameters)
    val offset = parseAdminListOffset(call.request.queryParameters)
    val status = parseModerationStatusFilter(call.request.queryParameters)
    call.respond(reportRepo.getReports(status, limit, offset))
}
}
