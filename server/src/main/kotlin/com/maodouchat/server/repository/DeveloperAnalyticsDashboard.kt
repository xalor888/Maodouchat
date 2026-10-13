package com.maodouchat.server.repository

import com.maodouchat.server.db.BotCommandLogs
import com.maodouchat.server.db.dayBucketExpression
import com.maodouchat.server.model.BotAnalyticsResponse
import com.maodouchat.server.model.CommandUsageEntry
import com.maodouchat.server.model.DailyStat
import com.maodouchat.server.model.DeveloperDashboardResponse
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.countDistinct
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 聚合查询内存上限：对 BotCommandLogs 做进程内 group-by，不限流会物化全表行导致 OOM。
 * 加行上限防止自残式 OOM（极繁忙 bot 退化为近似值）。
 */
private val maxAggRows = 50_000

private val dayMs = 86_400_000L

/** 开发者门户：Bot 概览看板聚合。 */
fun DeveloperAnalyticsRepository.dashboard(botId: String): DeveloperDashboardResponse = transaction {
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

/** 开发者门户：按天统计分析。 */
fun DeveloperAnalyticsRepository.analytics(botId: String, days: Int): BotAnalyticsResponse {
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

private fun DeveloperAnalyticsRepository.unixDayStartMs(epochMs: Long, dayMs: Long = this.dayMs): Long =
    epochMs - Math.floorMod(epochMs, dayMs)

