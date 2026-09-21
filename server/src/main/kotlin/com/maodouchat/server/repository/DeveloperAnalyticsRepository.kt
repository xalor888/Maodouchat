package com.maodouchat.server.repository

import com.maodouchat.server.db.BotCommandLogs
import com.maodouchat.server.db.dayBucketExpression
import com.maodouchat.server.model.BotAnalyticsResponse
import com.maodouchat.server.model.BotHealthStatus
import com.maodouchat.server.model.BotLogEntry
import com.maodouchat.server.model.BotLogsResponse
import com.maodouchat.server.model.CommandUsageEntry
import com.maodouchat.server.model.DailyStat
import com.maodouchat.server.model.DeveloperDashboardResponse
import com.maodouchat.server.model.DeveloperHealthResponse
import com.maodouchat.server.model.ServerHealthStatus
import com.maodouchat.server.service.RuntimeConfigService
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.countDistinct
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Expression

/**
 * 开发者门户只读聚合（G57 从 `plugins/DeveloperRouting.kt` 的裸事务下沉）。
 *
 * 四个入口对应四个端点：命令日志分页、看板、按天分析、健康探针。
 * 全部只读 `bot_command_logs`，不触碰任何消息正文 / 密文。
 */
class DeveloperAnalyticsRepository {

    /**
     * 聚合查询内存上限：对 BotCommandLogs 做进程内 group-by，不限流会物化全表行导致 OOM。
     * 加行上限防止自残式 OOM（极繁忙 bot 退化为近似值）。
     */
    private val maxAggRows = 50_000

    private val dayMs = 86_400_000L

    // ─── Structured logs ─────────────────────────────

    /**
     * 命令日志分页。`total` 是**过滤后的总行数**（不是本页条数）——
     * 客户端按 limit/offset 翻页时靠它判断是否还有下一页。
     */
    fun commandLogs(
        botId: String,
        commandFilter: String?,
        sinceMs: Long?,
        limit: Int,
        offset: Long,
    ): BotLogsResponse = transaction {
        var query = BotCommandLogs.selectAll().where { BotCommandLogs.botId eq botId }
        if (commandFilter != null) query = query.andWhere { BotCommandLogs.command eq commandFilter }
        if (sinceMs != null && sinceMs > 0) {
            query = query.andWhere { BotCommandLogs.createdAt greater sinceMs }
        }
        val total = query.count().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val rows = query.orderBy(
            BotCommandLogs.createdAt to SortOrder.DESC,
            BotCommandLogs.id to SortOrder.DESC,
        )
            .limit(limit, offset)
            .map { row ->
                BotLogEntry(
                    id = row[BotCommandLogs.id],
                    command = row[BotCommandLogs.command],
                    chatId = row[BotCommandLogs.chatId],
                    userId = row[BotCommandLogs.userId],
                    createdAt = row[BotCommandLogs.createdAt],
                )
            }
        BotLogsResponse(logs = rows, total = total)
    }

    // ─── Dashboard ───────────────────────────────────

    fun dashboard(botId: String): DeveloperDashboardResponse = transaction {
        val bot = BotRepository.get(botId)
        val totalCommands = BotCommandLogs.selectAll()
            .where { BotCommandLogs.botId eq botId }
            .count()

        val dayAgo = System.currentTimeMillis() - dayMs
        val commands24h = BotCommandLogs.selectAll()
            .where {
                (BotCommandLogs.botId eq botId) and (BotCommandLogs.createdAt greater dayAgo)
            }
            .count()

        // SQL 侧 COUNT(DISTINCT)，全表物化会 OOM
        val uniqueUsers24h = BotCommandLogs.select(BotCommandLogs.userId)
            .where {
                (BotCommandLogs.botId eq botId) and
                    (BotCommandLogs.createdAt greater dayAgo) and
                    BotCommandLogs.userId.isNotNull()
            }
            .withDistinct()
            .count()

        val topCommands = BotCommandLogs.selectAll()
            .where { BotCommandLogs.botId eq botId }
            .limit(maxAggRows)
            .map { it[BotCommandLogs.command] }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(10)
            .map { CommandUsageEntry(command = it.key, count = it.value) }

        DeveloperDashboardResponse(
            botId = botId,
            botName = bot?.name.orEmpty(),
            botUsername = bot?.username.orEmpty(),
            totalCommands = totalCommands,
            commandsLast24h = commands24h,
            uniqueUsersLast24h = uniqueUsers24h.toLong(),
            pendingUpdates = BotRepository.countPendingUpdates(botId),
            webhookConfigured = !bot?.webhookUrl.isNullOrBlank(),
            topCommands = topCommands,
            generatedAt = System.currentTimeMillis(),
        )
    }

    // ─── Analytics ───────────────────────────────────

    fun analytics(botId: String, days: Int): BotAnalyticsResponse {
        val now = System.currentTimeMillis()
        val cutoff = now - days * dayMs
        return transaction {
            val totalCommands = BotCommandLogs.selectAll()
                .where { (BotCommandLogs.botId eq botId) and (BotCommandLogs.createdAt greater cutoff) }
                .count()

            val dailyStats = buildList<DailyStat> {
                // 按天 GROUP BY 聚合（逐日 count 会让 30 天 = 60 次查询）
                val dayBucket: Expression<Long> = dayBucketExpression(BotCommandLogs.createdAt)
                val commandCounts = BotCommandLogs
                    .slice(dayBucket, BotCommandLogs.id.count())
                    .selectAll()
                    .where { (BotCommandLogs.botId eq botId) and (BotCommandLogs.createdAt greater cutoff) }
                    .groupBy(dayBucket)
                    .toList()
                    .associate { it[dayBucket] to it[BotCommandLogs.id.count()].toLong() }
                val uniqueCountExpr = BotCommandLogs.userId.countDistinct()
                val uniqueCounts = BotCommandLogs
                    .slice(dayBucket, uniqueCountExpr)
                    .selectAll()
                    .where {
                        (BotCommandLogs.botId eq botId) and
                            (BotCommandLogs.createdAt greater cutoff) and
                            BotCommandLogs.userId.isNotNull()
                    }
                    .groupBy(dayBucket)
                    .toList()
                    .associate { it[dayBucket] to it[uniqueCountExpr].toLong() }
                for (i in 0 until days) {
                    val dayStartNorm = unixDayStartMs(now - (days - 1 - i) * dayMs, dayMs)
                    add(
                        DailyStat(
                            day = dayStartNorm,
                            commandCount = commandCounts[dayStartNorm / dayMs] ?: 0,
                            uniqueUsers = uniqueCounts[dayStartNorm / dayMs] ?: 0,
                        )
                    )
                }
            }

            val commandBreakdown = BotCommandLogs.selectAll()
                .where { (BotCommandLogs.botId eq botId) and (BotCommandLogs.createdAt greater cutoff) }
                .limit(maxAggRows)
                .map { it[BotCommandLogs.command] }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(20)
                .map { CommandUsageEntry(command = it.key, count = it.value) }

            BotAnalyticsResponse(
                botId = botId,
                periodDays = days,
                totalCommands = totalCommands,
                dailyStats = dailyStats,
                commandBreakdown = commandBreakdown,
                generatedAt = now,
            )
        }
    }

    // ─── Health ──────────────────────────────────────

    fun health(botId: String): DeveloperHealthResponse = transaction {
        val bot = BotRepository.get(botId)
        val webhookHealthy = runCatching {
            bot != null && bot.enabled && !bot.webhookUrl.isNullOrBlank()
        }.getOrDefault(false)

        val commandCount = BotCommandLogs.selectAll()
            .where { BotCommandLogs.botId eq botId }
            .count()

        val botHealth = BotHealthStatus(
            botId = botId,
            enabled = bot?.enabled ?: false,
            webhookConfigured = !bot?.webhookUrl.isNullOrBlank(),
            webhookUrl = bot?.webhookUrl?.take(80),
            pendingUpdates = BotRepository.countPendingUpdates(botId),
            totalCommands = commandCount,
        )

        DeveloperHealthResponse(
            status = if (webhookHealthy && !RuntimeConfigService.isMaintenanceMode()) "healthy" else "degraded",
            bot = botHealth,
            server = ServerHealthStatus(
                serverTime = System.currentTimeMillis(),
                maintenanceMode = RuntimeConfigService.isMaintenanceMode(),
                botsAllowed = RuntimeConfigService.isBotsAllowed(),
                mediaUploadEnabled = RuntimeConfigService.isMediaUploadEnabled(),
                aiEnabled = RuntimeConfigService.isAiEnabled(),
            ),
            checkedAt = System.currentTimeMillis(),
        )
    }

    private fun unixDayStartMs(epochMs: Long, dayMs: Long = this.dayMs): Long =
        epochMs - Math.floorMod(epochMs, dayMs)
}
