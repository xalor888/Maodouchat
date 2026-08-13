package com.maodouchat.server.repository

import com.maodouchat.server.db.AiAuditLogs
import com.maodouchat.server.db.AiPreferences
import com.maodouchat.server.model.AiAuditLogResponse
import com.maodouchat.server.model.AiSettingsResponse
import org.jetbrains.exposed.sql.LongColumnType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

class AiRepository {

    private val logger = LoggerFactory.getLogger(AiRepository::class.java)
    private val tokenColumnsEnsured = AtomicBoolean(false)

    fun getSettings(userId: String, chatId: String? = null): AiSettingsResponse = transaction {
        getSettingsInTransaction(userId, chatId)
    }

    fun setUserEnabled(userId: String, enabled: Boolean): AiSettingsResponse = try {
        transaction {
            upsertPreference(userId, SCOPE_USER, "", enabled)
            getSettingsInTransaction(userId, null)
        }
    } catch (error: Exception) {
        if (!isUniqueViolation(error)) throw error
        // 并发首插撞 (userId, scope, chatId) PK：本事务已回滚，新事务重放 UPDATE
        transaction {
            upsertPreference(userId, SCOPE_USER, "", enabled)
            getSettingsInTransaction(userId, null)
        }
    }

    fun setChatEnabled(userId: String, chatId: String, enabled: Boolean): AiSettingsResponse = try {
        transaction {
            upsertPreference(userId, SCOPE_CHAT, chatId, enabled)
            getSettingsInTransaction(userId, chatId)
        }
    } catch (error: Exception) {
        if (!isUniqueViolation(error)) throw error
        transaction {
            upsertPreference(userId, SCOPE_CHAT, chatId, enabled)
            getSettingsInTransaction(userId, chatId)
        }
    }

    fun isEnabled(userId: String, chatId: String? = null): Boolean {
        return getSettings(userId, chatId).effectiveEnabled
    }

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
        transaction {
            ensureTokenColumns()
            val auditId = "ai_${UUID.randomUUID()}"
            AiAuditLogs.insert {
                it[id] = auditId
                it[AiAuditLogs.userId] = userId
                it[AiAuditLogs.chatId] = chatId?.takeIf(String::isNotBlank)
                it[AiAuditLogs.feature] = feature.take(40)
                it[AiAuditLogs.model] = model?.take(80)
                it[AiAuditLogs.status] = status.take(30)
                it[AiAuditLogs.inputChars] = inputChars.coerceAtLeast(0)
                it[AiAuditLogs.contextMessages] = contextMessages.coerceAtLeast(0)
                it[AiAuditLogs.durationMs] = durationMs
                it[AiAuditLogs.error] = error?.take(200)
                it[AiAuditLogs.createdAt] = System.currentTimeMillis()
            }
            // Token 列通过 ALTER TABLE 安全添加（Database.kt 不在本任务可编辑范围），
            // 这里用参数化 UPDATE 写入，避免修改 Table 单例。审计是尽力而为，不可让主流程失败。
            if (inputTokens != null || outputTokens != null) {
                runCatching {
                    TransactionManager.current().exec(
                        "UPDATE ai_audit_logs SET input_tokens = ?, output_tokens = ? WHERE id = ?",
                        listOf(
                            LongColumnType() to inputTokens,
                            LongColumnType() to outputTokens,
                            VarCharColumnType() to auditId
                        )
                    )
                }
            }
        }
    }

    /** 清理超过保留期的 AI 审计日志，防止无限增长。默认保留 90 天。 */
    fun purgeOldAuditLogs(retentionDays: Int = 90): Int {
        val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
        return transaction {
            AiAuditLogs.deleteWhere { AiAuditLogs.createdAt less cutoff }
        }
    }

    /**
     * 汇总某用户今日（服务器时区）已消耗的 input + output token 总量。
     * 供 AiGatewayService 的每用户每日预算检查使用。
     */
    fun sumTokensForUserToday(userId: String): Long {
        return transaction {
            ensureTokenColumns()
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

    /**
     * 服务器时区今日 00:00 的 epoch 毫秒。预算按此边界每日重置。
     */
    fun startOfTodayMillis(zone: ZoneId = ZoneId.systemDefault()): Long {
        val today = LocalDate.now(zone)
        return today.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /**
     * 幂等确保 token 列存在。H2 与 PostgreSQL 均支持 ADD COLUMN IF NOT EXISTS。
     * 仅在事务内执行；用 AtomicBoolean 保证每 JVM 只尝试一次，避免每次审计的开销。
     */
    private fun ensureTokenColumns() {
        if (tokenColumnsEnsured.get()) return
        val tx = TransactionManager.current()
        runCatching {
            tx.exec("ALTER TABLE ai_audit_logs ADD COLUMN IF NOT EXISTS input_tokens BIGINT")
            tx.exec("ALTER TABLE ai_audit_logs ADD COLUMN IF NOT EXISTS output_tokens BIGINT")
        }.onSuccess { tokenColumnsEnsured.set(true) }
            // Log failure and still flip the flag so we don't retry on every audit call;
            // the missing columns are non-fatal (token accounting degrades gracefully).
            .onFailure { e -> logger.warn("ensureTokenColumns failed: ${e.message}") }
            .onFailure { tokenColumnsEnsured.set(true) }
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

    private fun readPreference(userId: String, scope: String, chatId: String): Boolean? {
        return AiPreferences.selectAll()
            .where {
                (AiPreferences.userId eq userId) and
                    (AiPreferences.scope eq scope) and
                    (AiPreferences.chatId eq chatId)
            }
            .firstOrNull()
            ?.get(AiPreferences.enabled)
    }

    private fun getSettingsInTransaction(userId: String, chatId: String?): AiSettingsResponse {
        val userEnabled = readPreference(userId, SCOPE_USER, "") ?: true
        val normalizedChatId = chatId?.takeIf(String::isNotBlank)
        val chatEnabled = normalizedChatId?.let { readPreference(userId, SCOPE_CHAT, it) }
        return AiSettingsResponse(
            userEnabled = userEnabled,
            chatId = normalizedChatId,
            chatEnabled = chatEnabled,
            effectiveEnabled = userEnabled && (chatEnabled ?: true)
        )
    }

    private fun upsertPreference(userId: String, scope: String, chatId: String, enabled: Boolean) {
        // 先 UPDATE 再 INSERT：并发首插撞 (userId, scope, chatId) PK 时异常交给调用方
        // 事务外 catch 重试（PG abort 语义安全）。不用 Exposed upsert()——H2 2.x 不支持
        // 其生成的 MERGE ... USING (VALUES)（与 RateLimitStatsRepository.recordMinute
        // 的 isH2Db 分支同结论），生产 PG / 测试 H2 双兼容。
        val updated = AiPreferences.update({
            (AiPreferences.userId eq userId) and
                (AiPreferences.scope eq scope) and
                (AiPreferences.chatId eq chatId)
        }) {
            it[AiPreferences.enabled] = enabled
            it[updatedAt] = System.currentTimeMillis()
        }
        if (updated == 0) {
            AiPreferences.insert {
                it[AiPreferences.userId] = userId
                it[AiPreferences.scope] = scope
                it[AiPreferences.chatId] = chatId
                it[AiPreferences.enabled] = enabled
                it[updatedAt] = System.currentTimeMillis()
            }
        }
    }

    private fun isUniqueViolation(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty().lowercase()
            if (current is java.sql.SQLException && current.sqlState == "23505") return true
            if (message.contains("unique") || message.contains("duplicate key")) return true
            current = current.cause
        }
        return false
    }

    private companion object {
        const val SCOPE_USER = "USER"
        const val SCOPE_CHAT = "CHAT"
    }
}
