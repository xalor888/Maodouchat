package com.maodouchat.server.service

// 风控与审计类导出。

import com.maodouchat.server.repository.auditExportRows
import com.maodouchat.server.repository.moderationAudit
import com.maodouchat.server.repository.reports
import com.maodouchat.server.repository.reportsMeta
import com.maodouchat.server.repository.riskEvents

fun AdminExportService.moderationAuditCsv(limit: Int) = csv(
    "id,actorId,userId,action,detail,createdAt",
    repository.moderationAudit(limit),
)

fun AdminExportService.reportsCsv(limit: Int) = csv(
    "id,reporterId,targetType,targetId,reason,status,createdAt",
    repository.reports(limit),
)

fun AdminExportService.reportsMetaCsv(limit: Int) = csv(
    "id,reporterId,targetType,targetId,chatId,reason,status,actionTaken,createdAt",
    repository.reportsMeta(limit),
)

fun AdminExportService.riskEventsCsv(limit: Int) = csv(
    "id,userId,source,action,matched,needsReview,createdAt",
    repository.riskEvents(limit),
)

/**
 * 审计时间范围导出（BOM + CRLF，与其它导出不同）。
 *
 * 该格式是原 `buildAuditExportCsv` 的行为：UTF-8 BOM 开头、`\r\n` 分隔，
 * 句尾还有一个空行；Excel 依赖 BOM 才不会把中文当乱码。逐字保留。
 */
fun AdminExportService.auditExportCsv(scope: String, fromMs: Long, toMs: Long, limit: Int): AdminExportService.CsvExport {
    val rows = repository.auditExportRows(scope, fromMs, toMs, limit)
    val header = when (scope) {
        "ADMIN_AUDIT" -> "id,actorId,targetUserId,action,detail,createdAt"
        "RISK_EVENTS" -> "id,userId,source,ruleId,action,matched,referenceId,needsReview,createdAt"
        "ANNOUNCEMENTS" ->
            "id,title,content,level,audience,tagId,startsAt,expiresAt,status,createdBy,createdAt,publishedAt,cancelledAt"
        "USER_TAGS" -> "tagId,tagName,userId,source,assignedBy,createdAt"
        "RATE_LIMIT" -> "bucketStartMs,allowed,rejected,totalBuckets,maxBuckets,maxPerMinute,sampledAt"
        else -> ""
    }
    val body = rows.joinToString("\r\n") { row -> encodeRow(row) }
    return AdminExportService.CsvExport(body = "\uFEFF$header\r\n$body\r\n", rowCount = rows.size)
}
