package com.maodouchat.server.repository

import com.maodouchat.server.db.AiAuditLogs
import com.maodouchat.server.model.AiAuditLogResponse
import com.maodouchat.server.model.AiUsageAdminResponse
import com.maodouchat.server.service.AdminAiAuditPolicy
import org.jetbrains.exposed.sql.LongColumnType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.slf4j.LoggerFactory

class AiRepository {

    private val logger = LoggerFactory.getLogger(AiRepository::class.java)

    fun recordAudit(
        userId: String,
        chatId: String?,
        feature: String,
        model: String?,
        status: String,
        inputChars: Int,
        contextMessages: Int = 0,
        durationMs: Long? = null,
        error: String? = null,
        inputTokens: Long? = null,
        outputTokens: Long? = null
    ) {
        try {
            transaction {
                AiAuditLogs.insert {
                    it[id] = "ai_${UUID.randomUUID()}"
                    it[AiAuditLogs.userId] = userId
                    it[AiAuditLogs.chatId] = chatId?.takeIf(String::isNotBlank)
                    it[AiAuditLogs.feature] = feature.take(40)
                    it[AiAuditLogs.model] = model?.take(80)
                    it[AiAuditLogs.status] = status.take(30)
                    it[AiAuditLogs.inputChars] = inputChars.coerceAtLeast(0)
                    it[AiAuditLogs.contextMessages] = contextMessages.coerceAtLeast(0)
                    it[AiAuditLogs.durationMs] = durationMs
                    it[AiAuditLogs.error] = error?.take(200)
                    it[AiAuditLogs.inputTokens] = inputTokens
                    it[AiAuditLogs.outputTokens] = outputTokens
                    it[AiAuditLogs.createdAt] = System.currentTimeMillis()
                }
            }
        } catch (auditError: Exception) {
            logger.warn("recordAudit failed (best-effort): {}", auditError.message)
        }
    }

    fun purgeOldAuditLogs(retentionDays: Int = 90): Int {
        val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
        return transaction {
            AiAuditLogs.deleteWhere { AiAuditLogs.createdAt less cutoff }
        }
    }

    fun sumTokensForUserToday(userId: String): Long {
        return transaction {
            val startOfDay = startOfTodayMillis()
            TransactionManager.current().exec(
                "SELECT COALESCE(SUM(COALESCE(input_tokens,0) + COALESCE(output_tokens,0)),0) " +
                    "FROM ai_audit_logs WHERE user_id = ? AND created_at >= ?",
                listOf(VarCharColumnType() to userId, LongColumnType() to startOfDay)
            ) { rs ->
                if (rs.next()) rs.getLong(1) else 0L
            } ?: 0L
        }
    }

    fun startOfTodayMillis(zone: ZoneId = ZoneId.systemDefault()): Long {
        val today = LocalDate.now(zone)
        return today.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    fun getAuditLogs(userId: String, limit: Int = 50): List<AiAuditLogResponse> = transaction {
        AiAuditLogs.selectAll()
            .where { AiAuditLogs.userId eq userId }
            .orderBy(AiAuditLogs.createdAt to SortOrder.DESC, AiAuditLogs.id to SortOrder.DESC)
            .limit(limit.coerceIn(1, 100))
            .map {
                AiAuditLogResponse(
                    id = it[AiAuditLogs.id],
                    chatId = it[AiAuditLogs.chatId],
                    feature = it[AiAuditLogs.feature],
                    model = it[AiAuditLogs.model],
                    status = it[AiAuditLogs.status],
                    inputChars = it[AiAuditLogs.inputChars],
                    contextMessages = it[AiAuditLogs.contextMessages],
                    durationMs = it[AiAuditLogs.durationMs],
                    error = it[AiAuditLogs.error],
                    createdAt = it[AiAuditLogs.createdAt]
                )
            }
    }

    /**
     * 管理后台 AI 使用审计列表：仅元数据（绝不投影 chatId / prompt / 正文）。
     *
     * 从 `plugins/AdminDiagnosticsRouting.kt` 下沉：那段在路由里开裸事务，还用参数化裸 SQL
     * (`SELECT id, input_tokens, output_tokens ...`) 回填 token。token 列 9.137 起已进 Table 单例，
     * 因此这里直接读列，回填链路整体消失。
     */
    fun listAuditLogsForAdmin(
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
    fun auditExportRows(limit: Int): List<AiAuditExportRow> = transaction {
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

    data class AiAuditExportRow(
        val id: String,
        val userId: String,
        val feature: String,
        val status: String,
        val inputChars: Int,
        val contextMessages: Int,
        val durationMs: Long?,
        val error: String?,
        val createdAt: Long,
        val inputTokens: Long?,
        val outputTokens: Long?,
    )
}
