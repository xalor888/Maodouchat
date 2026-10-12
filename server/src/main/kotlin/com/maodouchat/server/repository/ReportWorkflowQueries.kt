package com.maodouchat.server.repository

import com.maodouchat.server.db.Reports
import com.maodouchat.server.model.ReportResponse
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun ReportWorkflow.getMyReports(reporterId: String, limit: Int = 50): List<ReportResponse> = transaction {
    Reports.selectAll()
        .where { Reports.reporterId eq reporterId }
        .orderBy(Reports.createdAt to SortOrder.DESC, Reports.id to SortOrder.DESC)
        .limit(limit.coerceIn(1, 100))
        .map { it.toResponse() }
}
fun ReportWorkflow.getReport(reportId: String): ReportResponse? = transaction {
    Reports.selectAll()
        .where { Reports.id eq reportId.trim() }
        .firstOrNull()
        ?.toResponse()
}
fun ReportWorkflow.getReports(status: String? = null, limit: Int = 50, offset: Long = 0): List<ReportResponse> = transaction {
    val normalizedStatus = status?.trim()?.uppercase()?.takeIf { it.isNotBlank() && it != "ALL" }
    val query = Reports.selectAll()
    if (normalizedStatus != null) query.andWhere { Reports.status eq normalizedStatus }
    query.orderBy(Reports.createdAt to SortOrder.DESC, Reports.id to SortOrder.DESC)
        .limit(limit.coerceIn(1, 200), offset.coerceAtLeast(0))
        .map { it.toResponse() }
}
/**
 * 清理办结超过保留期的举报记录（默认 365 天，按 resolvedAt 计），防止无限增长。
 * 未办结（OPEN/IN_REVIEW）的举报永不清理。由 Routing.kt 的周期清理循环调用。
 */
fun ReportWorkflow.purgeResolvedOlderThan(retentionDays: Long = 365): Int {
    val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
    return transaction {
        Reports.deleteWhere {
            (Reports.resolvedAt.isNotNull()) and (Reports.resolvedAt less cutoff)
        }
    }
}
