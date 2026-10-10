package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.BlobStore
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** 审核员：举报列表/改状态/执行处置。 */
internal fun Route.configureModeratorReviewRoutes(
    userRepo: UserRepository,
    postRepo: PostRepository,
    reportRepo: ReportWorkflow,
    sessionService: com.maodouchat.server.service.SessionService,
    conversationParticipantRepo: ConversationParticipantRepository,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
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

        put("/api/moderator/reports/{reportId}/status") {
            val uid = call.requireUserId()
            if (!hasContentModerationAccess(userRepo, uid)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要审核员权限"))
                return@put
            }
            val reportId = parseRawOrEmpty(call.parameters, "reportId")
            val req = call.receiveJsonOr400<UpdateReportStatusRequest>() ?: return@put
            when (val result = reportRepo.updateReportStatus(reportId, uid, req.status, req.resolutionNote)) {
                is ReportWorkflow.UpdateResult.Success -> call.respond(result.report)
                is ReportWorkflow.UpdateResult.Failure -> {
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

        post("/api/moderator/reports/{reportId}/action") {
            val uid = call.requireUserId()
            if (!hasContentModerationAccess(userRepo, uid)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要审核员权限"))
                return@post
            }
            val reportId = parseRawOrEmpty(call.parameters, "reportId")
            val req = call.receiveJsonOr400<ApplyReportActionRequest>() ?: return@post
            val action = req.action.trim().uppercase()
            val existingReport = reportRepo.getReport(reportId) ?: run {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("举报不存在"))
                return@post
            }
            // 只读校验在标记前完成；处置对象 userId 在此冻结，避免 mark 后内容被删导致限制落空
            val frozenRestrictionTargetUserId: String? = when (action) {
                "NO_ACTION" -> null
                "DELETE_CONTENT" -> {
                    if (existingReport.targetType !in setOf("MESSAGE", "POST", "COMMENT")) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("该举报类型不能删除内容"))
                        return@post
                    }
                    null
                }
                "RESTRICT_MESSAGES_24H", "RESTRICT_POSTS_7D", "SUSPEND_24H" -> {
                    val targetUserId = when (existingReport.targetType) {
                        "USER" -> existingReport.targetId
                        "MESSAGE" -> messagingV2Repository
                            .messageMetadata(existingReport.messageId ?: existingReport.targetId)
                            ?.senderUserId
                        "POST" -> postRepo.getPostAuthorId(existingReport.targetId)
                        "COMMENT" -> postRepo.getCommentAuthorId(existingReport.targetId)
                        else -> null
                    }
                    if (targetUserId.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("无法定位被处置用户"))
                        return@post
                    }
                    if (targetUserId == uid) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("不能处置自己"))
                        return@post
                    }
                    if (hasContentModerationAccess(userRepo, targetUserId)) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("不能通过举报处置审核员或超级管理员账号"))
                        return@post
                    }
                    targetUserId
                }
                else -> {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("处置动作无效"))
                    return@post
                }
            }
            // 处置副作用先于 actionTaken 提交：回调失败时举报保持可重试，
            // 不再出现「已标记已处置但内容仍在」的不可恢复状态。
            var deletedModeration: com.maodouchat.server.messaging.v2.MessagingV2ModerationDeleteResult? = null
            var broadcastPostDeletionFor: String? = null
            when (
                val mark = reportRepo.executeActionAfterBusinessSuccess(
                    reportId = reportId,
                    reviewerId = uid,
                    action = action,
                    resolutionNote = req.resolutionNote,
                    businessAction = { pending ->
                        when (action) {
                            "NO_ACTION" -> true
                            "DELETE_CONTENT" -> when (pending.targetType) {
                                "MESSAGE" -> {
                                    val messageId = pending.messageId ?: pending.targetId
                                    val repository = messagingV2Repository
                                    val deleted = repository.deleteMessageForModeration(messageId)
                                    if (deleted != null) {
                                        deletedModeration = deleted
                                        true
                                    } else {
                                        // 目标已不存在（重复处置）视作成功，避免审核入口卡死。
                                        repository.messageMetadata(messageId) == null
                                    }
                                }
                                "POST" -> {
                                    val deleted = postRepo.deletePostForModeration(pending.targetId)
                                    if (deleted) broadcastPostDeletionFor = pending.targetId
                                    deleted
                                }
                                "COMMENT" -> postRepo.deleteCommentForModeration(pending.targetId)
                                else -> true
                            }
                            "RESTRICT_MESSAGES_24H", "RESTRICT_POSTS_7D", "SUSPEND_24H" -> {
                                val targetUserId = frozenRestrictionTargetUserId
                                if (targetUserId.isNullOrBlank() || targetUserId == uid ||
                                    hasContentModerationAccess(userRepo, targetUserId)
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
                    call.respond(status, ErrorResponse(mark.message))
                    return@post
                }
                is ReportWorkflow.ExecuteActionResult.AlreadyDone -> {
                    call.respond(mark.report)
                    return@post
                }
                is ReportWorkflow.ExecuteActionResult.BusinessActionFailed -> {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("处置执行失败，请稍后重试"))
                    return@post
                }
                is ReportWorkflow.ExecuteActionResult.Completed -> {
                    val report = mark.report
                    when (action) {
                        "DELETE_CONTENT" -> {
                            broadcastPostDeletionFor?.let { broadcastPostDeleted(it) }
                            deletedModeration?.let { deleted ->
                                deleted.deletedAttachmentIds.forEach(BlobStore::delete)
                                fanoutSystemDelete(
                                    conversationParticipantRepo,
                                    json,
                                    deleted.metadata.conversationId,
                                    report.messageId ?: report.targetId,
                                    messagingV2Repository,
                                )
                            }
                        }
                        "RESTRICT_MESSAGES_24H", "RESTRICT_POSTS_7D", "SUSPEND_24H" -> {
                            val targetUserId = frozenRestrictionTargetUserId
                            if (action == "SUSPEND_24H" && !targetUserId.isNullOrBlank()) {
                                sessionService.revokeAllUserSessions(targetUserId)
                                disconnectUserSessions(targetUserId, "账号已被临时封禁")
                            }
                        }
                        else -> Unit
                    }
                    call.respond(report)
                }
            }
        }
    }
}
