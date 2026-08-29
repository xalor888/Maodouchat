package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.EncryptedAttachmentStorage
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

internal fun Route.configureReportModerationRoutes(
    userRepo: UserRepository,
    postRepo: PostRepository,
    reportRepo: ReportRepository,
    moderationRuleRepo: ModerationRuleRepository,
    authTokenRepo: AuthTokenRepository,
    pushTokenRepo: PushTokenRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    reportRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    authenticate("auth-jwt") {
            post("/api/users/block/{uid}") {
                if (!RuntimeConfigService.isBlockReportEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("block_report_disabled"))
                    return@post
                }
                val blockerId = call.principal<JWTPrincipal>()!!.payload.subject
                val blockedId = call.parameters["uid"].orEmpty()
                if (!userRepo.blockUser(blockerId, blockedId)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("无法拉黑该用户"))
                    return@post
                }
                call.respond(
                buildJsonObject {
put("status", "ok")
                }
            )
            }
            delete("/api/users/block/{uid}") { userRepo.unblockUser(call.principal<JWTPrincipal>()!!.payload.subject, call.parameters["uid"]!!); call.respond(
                buildJsonObject {
put("status", "ok")
                }
            ) }
            get("/api/users/blocks") { call.respond(userRepo.getBlockedUsers(call.principal<JWTPrincipal>()!!.payload.subject)) }
            get("/api/users/blocks/details") { call.respond(userRepo.getBlockedUserDetails(call.principal<JWTPrincipal>()!!.payload.subject)) }
            post("/api/reports") {

                if (!RuntimeConfigService.isBlockReportEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("block_report_disabled"))
                    return@post
                }
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                if (!reportRateLimiter.acquire(uid, maxPerMinute = 5)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("举报过于频繁，请稍后再试"))
                    return@post
                }
                val req = call.receiveBoundedText()?.let { parseJson<CreateReportRequest>(it) } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@post
                }
                when (val result = reportRepo.createReport(uid, req)) {
                    is ReportRepository.CreateResult.Success -> call.respond(HttpStatusCode.Created, result.report)
                    is ReportRepository.CreateResult.Failure -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
                }
            }

            get("/api/reports/mine") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 100)
                call.respond(reportRepo.getMyReports(uid, limit))
            }

            // 路径从 /api/admin/reports 改为 /api/moderator/reports，避免与 AdminRouting.kt 的 master admin 版本冲突
            // admin.js（web admin，admin-jwt）仍走 /api/admin/reports；app 客户端（moderator，auth-jwt）走此路径
            get("/api/moderator/reports") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                if (!hasContentModerationAccess(userRepo, uid)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要审核员权限"))
                    return@get
                }
                val status = call.request.queryParameters["status"]
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 200)
                val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                call.respond(reportRepo.getReports(status, limit, offset))
            }

            put("/api/moderator/reports/{reportId}/status") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                if (!hasContentModerationAccess(userRepo, uid)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要审核员权限"))
                    return@put
                }
                val reportId = call.parameters["reportId"].orEmpty()
                val req = call.receiveBoundedText()?.let { parseJson<UpdateReportStatusRequest>(it) } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@put
                }
                when (val result = reportRepo.updateReportStatus(reportId, uid, req.status, req.resolutionNote)) {
                    is ReportRepository.UpdateResult.Success -> call.respond(result.report)
                    is ReportRepository.UpdateResult.Failure -> {
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
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                if (!hasContentModerationAccess(userRepo, uid)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要审核员权限"))
                    return@post
                }
                val reportId = call.parameters["reportId"].orEmpty()
                val req = call.receiveBoundedText()?.let { parseJson<ApplyReportActionRequest>(it) } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@post
                }
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
                            "MESSAGE" -> com.maodouchat.server.messaging.v2.MessagingV2Repository()
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
                                        val repository = com.maodouchat.server.messaging.v2.MessagingV2Repository()
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
                    is ReportRepository.ExecuteActionResult.Failure -> {
                        val status = if (mark.message == "举报不存在") HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                        call.respond(status, ErrorResponse(mark.message))
                        return@post
                    }
                    is ReportRepository.ExecuteActionResult.AlreadyDone -> {
                        call.respond(mark.report)
                        return@post
                    }
                    is ReportRepository.ExecuteActionResult.BusinessActionFailed -> {
                        call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("处置执行失败，请稍后重试"))
                        return@post
                    }
                    is ReportRepository.ExecuteActionResult.Completed -> {
                        val report = mark.report
                        when (action) {
                            "DELETE_CONTENT" -> {
                                broadcastPostDeletionFor?.let { broadcastPostDeleted(it) }
                                deletedModeration?.let { deleted ->
                                    deleted.deletedAttachmentIds.forEach(EncryptedAttachmentStorage::delete)
                                    fanoutSystemDelete(
                                        conversationParticipantRepo,
                                        json,
                                        deleted.metadata.conversationId,
                                        report.messageId ?: report.targetId,
                                    )
                                }
                            }
                            "RESTRICT_MESSAGES_24H", "RESTRICT_POSTS_7D", "SUSPEND_24H" -> {
                                val targetUserId = frozenRestrictionTargetUserId
                                if (action == "SUSPEND_24H" && !targetUserId.isNullOrBlank()) {
                                    authTokenRepo.rotateAccessTokenVersion(targetUserId)
                                    pushTokenRepo.removeAllForUser(targetUserId)
                                    disconnectUserSessions(targetUserId, "账号已被临时封禁")
                                }
                            }
                            else -> Unit
                        }
                        call.respond(report)
                    }
                }
            }

            get("/api/admin/moderation/rules") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                if (!hasContentModerationAccess(userRepo, uid)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要审核员权限"))
                    return@get
                }
                call.respond(moderationRuleRepo.getRules())
            }

            put("/api/admin/moderation/rules/{ruleId}") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                if (!hasContentModerationAccess(userRepo, uid)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要审核员权限"))
                    return@put
                }
                val ruleId = call.parameters["ruleId"].orEmpty()
                val req = call.receiveBoundedText()?.let { parseJson<UpdateModerationRuleRequest>(it) } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@put
                }
                // 8.32 一致性：资源不存在 404、参数问题 400（此前合并为一个 400）
                if (!moderationRuleRepo.ruleExists(ruleId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("规则不存在"))
                    return@put
                }
                val updated = moderationRuleRepo.updateRule(ruleId, req)
                if (updated == null) call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                else call.respond(updated)
            }

            get("/api/admin/moderation/events") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                if (!hasContentModerationAccess(userRepo, uid)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要审核员权限"))
                    return@get
                }
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 200)
                val needsReview = call.request.queryParameters["needsReview"]?.toBooleanStrictOrNull()
                call.respond(moderationRuleRepo.getRiskEvents(limit, needsReview))
            }

            post("/api/admin/moderation/events/{eventId}/ack") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                if (!hasContentModerationAccess(userRepo, uid)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要审核员权限"))
                    return@post
                }
                val eventId = call.parameters["eventId"].orEmpty()
                if (!moderationRuleRepo.acknowledgeRiskEvent(eventId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("风险事件不存在"))
                    return@post
                }
                call.respond(
                buildJsonObject {
put("status", "ok")
                }
            )
            }
    }
}
