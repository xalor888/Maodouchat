package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.UpdateReportStatusRequest
import com.maodouchat.server.repository.ReportWorkflow
import com.maodouchat.server.repository.UpdateResult
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.updateReportStatus
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put

/** 审核员：举报改状态。 */
internal fun Route.configureModeratorReviewStatusRoutes(
    userRepo: UserRepository,
    reportRepo: ReportWorkflow,
) {
    authenticate("auth-jwt") {
        put("/api/moderator/reports/{reportId}/status") {
            val uid = call.requireUserId()
            if (!hasContentModerationAccess(userRepo, uid)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要审核员权限"))
                return@put
            }
            val reportId = parseRawOrEmpty(call.parameters, "reportId")
            val req = call.receiveJsonOr400<UpdateReportStatusRequest>() ?: return@put
            when (val result = reportRepo.updateReportStatus(reportId, uid, req.status, req.resolutionNote)) {
                is UpdateResult.Success -> call.respond(result.report)
                is UpdateResult.Failure -> {
                    // 8.42：资源不存在 404、状态冲突 409 与参数错误 400 分离
                    val status = when (result.message) {
                        "举报不存在" -> HttpStatusCode.NotFound
                        "已处置的举报不能变更状态" -> HttpStatusCode.Conflict
                        else -> HttpStatusCode.BadRequest
                    }
                    call.respond(status, ErrorResponse(result.message))
                }
            }
        }
    }
}
