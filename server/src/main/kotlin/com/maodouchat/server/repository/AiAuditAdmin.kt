package com.maodouchat.server.repository

import com.maodouchat.server.db.AiAuditLogs
import com.maodouchat.server.model.AiUsageAdminResponse
import com.maodouchat.server.service.AdminAiAuditPolicy
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

// AI 审计管理后台查询与导出。

/**
 * 管理后台 AI 使用审计列表：仅元数据（绝不投影 chatId / prompt / 正文）。
 *
 * 从 `plugins/AdminDiagnosticsRouting.kt` 下沉：那段在路由里开裸事务，还用参数化裸 SQL
 * (`SELECT id, input_tokens, output_tokens ...`) 回填 token。token 列 9.137 起已进 Table 单例，
 * 因此这里直接读列，回填链路整体消失。
 */
fun AiRepository.listAuditLogsForAdmin(
    limit: Int,
    offset: Long,
    featureFilter: String?,
    userFilter: String?,
): List<AiUsageAdminResponse> = transaction {
    val query = AiAuditLogs.selectAll()
    if (featureFilter != null) query.andWhere { AiAuditLogs.feature eq featureFilter }
    if (userFilter != null) query.andWhere { AiAuditLogs.userId eq userFilter }
    query.orderBy(AiAuditLogs.createdAt to SortOrder.DESC, AiAuditLogs.id to SortOrder.DESC)
        .limit(limit, offset)
        .map {
            AdminAiAuditPolicy.toAdminResponse(
                id = it[AiAuditLogs.id],
                userId = it[AiAuditLogs.userId],
                feature = it[AiAuditLogs.feature],
                model = it[AiAuditLogs.model],
                status = it[AiAuditLogs.status],
                inputChars = it[AiAuditLogs.inputChars],
                contextMessages = it[AiAuditLogs.contextMessages],
                durationMs = it[AiAuditLogs.durationMs],
                error = it[AiAuditLogs.error],
                createdAt = it[AiAuditLogs.createdAt],
                inputTokens = it[AiAuditLogs.inputTokens],
                outputTokens = it[AiAuditLogs.outputTokens],
            )
        }
}


/**
 * 管理后台 AI 用量导出的数据行（CSV 呈现留在路由层）。
 *
 * 从 `plugins/AdminBulkRouting.kt` 的裸事务 + 参数化裸 SQL 回填下沉：token 列 9.137 起已进
 * Table 单例，直接读列即可，那条 `SELECT id, input_tokens, output_tokens ...` 整体消失。
 * 只返回元数据，绝不包含 prompt / 正文。
 */
fun AiRepository.auditExportRows(limit: Int): List<AiAuditExportRow> = transaction {
    AiAuditLogs.selectAll()
        .orderBy(AiAuditLogs.createdAt to SortOrder.DESC, AiAuditLogs.id to SortOrder.DESC)
        .limit(limit)
        .map {
            AiAuditExportRow(
                id = it[AiAuditLogs.id],
                userId = it[AiAuditLogs.userId],
                feature = it[AiAuditLogs.feature],
                status = it[AiAuditLogs.status],
                inputChars = it[AiAuditLogs.inputChars],
                contextMessages = it[AiAuditLogs.contextMessages],
                durationMs = it[AiAuditLogs.durationMs],
                error = it[AiAuditLogs.error],
                createdAt = it[AiAuditLogs.createdAt],
                inputTokens = it[AiAuditLogs.inputTokens],
                outputTokens = it[AiAuditLogs.outputTokens],
            )
        }
}
