package com.maodouchat.server.plugins

import com.maodouchat.server.config.AdminAccess
import com.maodouchat.server.model.ApplyReportActionRequest
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.UpdateReportStatusRequest
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.ReportWorkflow
import com.maodouchat.server.repository.UserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun Route.configureAdminReportRoutes(
    reportRepo: ReportWorkflow,
    postRepo: PostRepository,
    userRepo: UserRepository,
    sessionService: com.maodouchat.server.service.SessionService,
) {
// 举报管理（admin-jwt 代理）
    get("/reports") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val limit = parseAdminListLimit(call.request.queryParameters)
        val offset = parseAdminListOffset(call.request.queryParameters)
        val status = parseModerationStatusFilter(call.request.queryParameters)
        call.respond(reportRepo.getReports(status, limit, offset))
    }

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

    post("/reports/{reportId}/action") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val actorId = call.requireUserId()
        val reportId = parseRawOrEmpty(call.parameters, "reportId")
        val req = call.receiveAdminJson<ApplyReportActionRequest>()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
        val action = req.action.trim().uppercase()
        val existingReport = reportRepo.getReport(reportId)
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("举报不存在"))
        // 处置对象 userId 在 mark 前冻结，避免 mark 后内容被删导致限制落空
        val frozenRestrictionTargetUserId: String? = when (action) {
            "NO_ACTION" -> null
            "DELETE_CONTENT" -> {
                if (existingReport.targetType !in setOf("POST", "COMMENT")) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("该举报类型不支持从管理面板删除内容，请使用审核员面板处理消息举报")
                    )
                }
                null
            }
            "RESTRICT_MESSAGES_24H", "RESTRICT_POSTS_7D", "SUSPEND_24H" -> {
                val targetUserId = when (existingReport.targetType) {
                    "USER" -> existingReport.targetId
                    "POST" -> postRepo.getPostAuthorId(existingReport.targetId)
                    "COMMENT" -> postRepo.getCommentAuthorId(existingReport.targetId)
                    else -> null
                }
                if (targetUserId.isNullOrBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("无法定位被处置用户"))
                }
                if (targetUserId == actorId) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("不能处置自己"))
                }
                if (AdminAccess.isAdmin(targetUserId)) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("不能处置超级管理员"))
                }
                targetUserId
            }
            else -> return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("处置动作无效"))
        }
        // 处置副作用先于 actionTaken 提交：回调失败时举报保持可重试
        when (
            val mark = reportRepo.executeActionAfterBusinessSuccess(
                reportId = reportId,
                reviewerId = actorId,
                action = action,
                resolutionNote = req.resolutionNote,
                businessAction = { pending ->
                    when (action) {
                        "NO_ACTION" -> true
                        "DELETE_CONTENT" -> when (pending.targetType) {
                            "POST" -> postRepo.deletePostForModeration(pending.targetId)
                            "COMMENT" -> postRepo.deleteCommentForModeration(pending.targetId)
                            else -> true
                        }
                        "RESTRICT_MESSAGES_24H", "RESTRICT_POSTS_7D", "SUSPEND_24H" -> {
                            val targetUserId = frozenRestrictionTargetUserId
                            if (targetUserId.isNullOrBlank() || targetUserId == actorId ||
                                AdminAccess.isAdmin(targetUserId)
                            ) {
                                false
                            } else {
                                userRepo.applyModerationRestriction(targetUserId, action)
                                true
                            }
                        }
                        else -> false
                    }
                },
            )
        ) {
            is ReportWorkflow.ExecuteActionResult.Failure -> {
                val status = if (mark.message == "举报不存在") HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                return@post call.respond(status, ErrorResponse(mark.message))
            }
            is ReportWorkflow.ExecuteActionResult.AlreadyDone ->
                return@post call.respond(
                    buildJsonObject {
                        put("status", "resolved")
                        put("action", (mark.report.actionTaken ?: "NO_ACTION"))
                    }
                )
            is ReportWorkflow.ExecuteActionResult.BusinessActionFailed ->
                return@post call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("处置执行失败，请稍后重试"))
            is ReportWorkflow.ExecuteActionResult.Completed -> {
                if (action == "SUSPEND_24H") {
                    frozenRestrictionTargetUserId?.let { targetUserId ->
                        sessionService.revokeAllUserSessions(targetUserId)
                        disconnectUserSessions(targetUserId, "账号已被临时封禁")
                    }
                }
                recordAdminAudit(actorId, "REPORT_ACTION_APPLIED", "reportId=$reportId; action=$action")
                call.respond(
                    buildJsonObject {
                        put("status", "resolved")
                        put("action", action)
                    }
                )
            }
        }
    }
}
