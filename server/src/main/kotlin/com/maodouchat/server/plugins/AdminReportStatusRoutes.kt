package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.UpdateReportStatusRequest
import com.maodouchat.server.repository.ReportWorkflow
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.routing.Route

/** 举报状态变更。 */
internal fun Route.configureAdminReportStatusRoutes(
    reportRepo: ReportWorkflow,
) {
put("/reports/{reportId}/status") {
    if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
    val actorId = call.requireUserId()
    val reportId = parseRawOrEmpty(call.parameters, "reportId")
    val req = call.receiveAdminJson<UpdateReportStatusRequest>()
        ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
    when (val result = reportRepo.updateReportStatus(reportId, actorId, req.status, req.resolutionNote)) {
        is ReportWorkflow.UpdateResult.Success -> call.respond(result.report)
        is ReportWorkflow.UpdateResult.Failure -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
    }
}
}
