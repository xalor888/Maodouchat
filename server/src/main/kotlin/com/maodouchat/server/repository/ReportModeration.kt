package com.maodouchat.server.repository

// 举报审核通道：状态流转/处置执行/动作标记（原 ReportWorkflowLifecycle 簇成员按域迁出，零行为改动）。
import com.maodouchat.server.db.ModerationAuditLog
import com.maodouchat.server.db.Reports
import com.maodouchat.server.model.ReportResponse
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun ReportWorkflow.updateReportStatus(reportId: String, reviewerId: String, status: String, resolutionNote: String?): UpdateResult = transaction {
    val normalizedId = reportId.trim()
    val normalizedStatus = status.trim().uppercase()
    if (normalizedId.isBlank()) return@transaction UpdateResult.Failure("举报不存在")
    if (normalizedStatus !in ALLOWED_STATUSES) return@transaction UpdateResult.Failure("举报状态无效")
    val existing = Reports.selectAll().where { Reports.id eq normalizedId }.firstOrNull()
        ?: return@transaction UpdateResult.Failure("举报不存在")
    // 8.34 修复：已执行处置动作的举报状态不可变——此前 RESOLVED（已封禁/已删内容）
    // → REJECTED 翻转畅通无阻：处罚副作用仍在，举报状态却读作「未违规」，审核视图
    // 按 REJECTED 过滤会漏掉已处罚目标。仅允许幂等同状态（重试）返回。
    val alreadyActioned = !existing[Reports.actionTaken].isNullOrBlank()
    if (alreadyActioned) {
        if (normalizedStatus != existing[Reports.status]) {
            return@transaction UpdateResult.Failure("已处置的举报不能变更状态")
        }
        return@transaction UpdateResult.Success(existing.toResponse())
    }
    val note = resolutionNote?.trim()?.take(MAX_RESOLUTION_NOTE_LENGTH)?.takeIf { it.isNotBlank() }
    val resolvedAt = if (normalizedStatus in FINAL_STATUSES) System.currentTimeMillis() else null
    Reports.update({ Reports.id eq existing[Reports.id] }) {
        it[Reports.status] = normalizedStatus
        it[Reports.reviewerId] = reviewerId
        it[Reports.resolutionNote] = note
        it[Reports.resolvedAt] = resolvedAt
    }
    ModerationAuditLog.insert {
        it[ModerationAuditLog.actorId] = reviewerId
        it[ModerationAuditLog.userId] = existing[Reports.targetId].takeIf { existing[Reports.targetType] == "USER" }
        it[ModerationAuditLog.action] = "REPORT_STATUS_UPDATE"
        it[ModerationAuditLog.detail] =
            "reportId=$normalizedId; reason=${existing[Reports.reason]}; before=${existing[Reports.status]}; after=$normalizedStatus"
        it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
    }
    UpdateResult.Success(Reports.selectAll().where { Reports.id eq normalizedId }.first().toResponse())
}

/**
 * Executes the durable business mutation before committing `actionTaken`.
 * The callback must be transaction-safe and return false when its requested
 * state change did not happen. Exceptions roll back the report update and
 * are deliberately propagated to preserve retry semantics.
 */
fun ReportWorkflow.executeActionAfterBusinessSuccess(
    reportId: String,
    reviewerId: String,
    action: String,
    resolutionNote: String?,
    businessAction: (ReportResponse) -> Boolean,
): ExecuteActionResult = transaction {
    val normalizedId = reportId.trim()
    val normalizedAction = action.trim().uppercase()
    if (normalizedAction !in ALLOWED_ACTIONS) return@transaction ExecuteActionResult.Failure("处置动作无效")
    val existing = Reports.selectAll().where { Reports.id eq normalizedId }.forUpdate().firstOrNull()
        ?: return@transaction ExecuteActionResult.Failure("举报不存在")
    if (!existing[Reports.actionTaken].isNullOrBlank() || existing[Reports.status] in FINAL_STATUSES) {
        return@transaction ExecuteActionResult.AlreadyDone(existing.toResponse())
    }
    val pendingReport = existing.toResponse()
    if (!businessAction(pendingReport)) return@transaction ExecuteActionResult.BusinessActionFailed(pendingReport)
    val now = System.currentTimeMillis()
    val note = resolutionNote?.trim()?.take(MAX_RESOLUTION_NOTE_LENGTH)?.takeIf { it.isNotBlank() }
        ?: existing[Reports.resolutionNote]
    Reports.update({ Reports.id eq existing[Reports.id] }) {
        it[Reports.status] = "RESOLVED"
        it[Reports.reviewerId] = reviewerId
        it[Reports.resolutionNote] = note
        it[Reports.actionTaken] = normalizedAction
        it[Reports.actionAt] = now
        it[Reports.resolvedAt] = now
    }
    ModerationAuditLog.insert {
        it[ModerationAuditLog.actorId] = reviewerId
        it[ModerationAuditLog.userId] = existing[Reports.targetId].takeIf { existing[Reports.targetType] == "USER" }
        it[ModerationAuditLog.action] = "REPORT_ACTION_APPLIED"
        it[ModerationAuditLog.detail] =
            "reportId=$normalizedId; reason=${existing[Reports.reason]}; before=${existing[Reports.status]}; after=RESOLVED; action=$normalizedAction"
        it[ModerationAuditLog.createdAt] = now
    }
    ExecuteActionResult.Completed(Reports.selectAll().where { Reports.id eq normalizedId }.first().toResponse())
}

/**
 * Compatibility adapter for routes that still perform business work after
 * claiming a report. New callers must use [executeActionAfterBusinessSuccess].
 */
fun ReportWorkflow.markActionTaken(reportId: String, reviewerId: String, action: String, resolutionNote: String?): ActionMarkResult = transaction {
    val normalizedId = reportId.trim()
    val normalizedAction = action.trim().uppercase()
    if (normalizedAction !in ALLOWED_ACTIONS) return@transaction ActionMarkResult.Failure("处置动作无效")
    val existing = Reports.selectAll().where { Reports.id eq normalizedId }.forUpdate().firstOrNull()
        ?: return@transaction ActionMarkResult.Failure("举报不存在")
    if (!existing[Reports.actionTaken].isNullOrBlank() || existing[Reports.status] in FINAL_STATUSES) {
        return@transaction ActionMarkResult.AlreadyDone(existing.toResponse())
    }
    val now = System.currentTimeMillis()
    val note = resolutionNote?.trim()?.take(MAX_RESOLUTION_NOTE_LENGTH)?.takeIf { it.isNotBlank() }
        ?: existing[Reports.resolutionNote]
    Reports.update({ Reports.id eq existing[Reports.id] }) {
        it[Reports.status] = "RESOLVED"
        it[Reports.reviewerId] = reviewerId
        it[Reports.resolutionNote] = note
        it[Reports.actionTaken] = normalizedAction
        it[Reports.actionAt] = now
        it[Reports.resolvedAt] = now
    }
    ModerationAuditLog.insert {
        it[ModerationAuditLog.actorId] = reviewerId
        it[ModerationAuditLog.userId] = existing[Reports.targetId].takeIf { existing[Reports.targetType] == "USER" }
        it[ModerationAuditLog.action] = "REPORT_ACTION_APPLIED"
        it[ModerationAuditLog.detail] =
            "reportId=$normalizedId; reason=${existing[Reports.reason]}; before=${existing[Reports.status]}; after=RESOLVED; action=$normalizedAction"
        it[ModerationAuditLog.createdAt] = now
    }
    ActionMarkResult.Applied(Reports.selectAll().where { Reports.id eq normalizedId }.first().toResponse())
}
