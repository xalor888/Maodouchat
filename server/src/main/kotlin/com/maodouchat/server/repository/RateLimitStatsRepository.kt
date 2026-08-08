package com.maodouchat.server.repository

import com.maodouchat.server.db.RateLimitStatsSnapshots
import com.maodouchat.server.plugins.GlobalRateLimiter
import com.maodouchat.server.plugins.RateLimitStats
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert

/**
 * 限流仪表盘仓储：每 60s 采样 GlobalRateLimiter 的累计计数器写入分钟桶，
 * 仪表盘按时间范围聚合；同时负责清理过期快照。
 *
 * 注意：GlobalRateLimiter.stats() 返回的是进程启动以来的累计值，
 * 仪表盘对相邻桶做差值即得该分钟内的增量，首桶（启动后第一分钟）差值为 0 属正常。
 */
class RateLimitStatsRepository {

    /** 分钟桶保留天数（默认 31 天，可经 env 覆盖）。 */
    val retentionDays: Int =
        envInt("RATE_LIMIT_STATS_RETENTION_DAYS", 31)

    data class MinuteBucket(
        val bucketStartMs: Long,
        val allowed: Long,
        val rejected: Long,
        val totalBuckets: Int,
        val maxBuckets: Int,
        val maxPerMinute: Int,
        val sampledAt: Long
    )

    data class SummaryPoint(
        val bucketStartMs: Long,
        val allowedDelta: Long,
        val rejectedDelta: Long,
        val avgTotalBuckets: Long,
        val maxTotalBuckets: Long,
        val maxPerMinute: Int
    )

    data class DashboardSummary(
        val points: List<SummaryPoint>,
        val totalAllowed: Long,
        val totalRejected: Long,
        val peakRejectionsPerMinute: Long,
        val live: RateLimitStats
    )

    /** 写入/更新一个分钟桶（幂等，按 bucketStartMs 唯一）。 */
    fun recordMinute(now: Long = System.currentTimeMillis()) {
        val stats = GlobalRateLimiter.getInstance().stats()
        val bucketStart = now - now % 60_000L
        transaction {
            RateLimitStatsSnapshots.upsert(RateLimitStatsSnapshots.bucketStartMs) {
                it[RateLimitStatsSnapshots.bucketStartMs] = bucketStart
                it[RateLimitStatsSnapshots.allowed] = stats.allowed
                it[RateLimitStatsSnapshots.rejected] = stats.rejected
                it[RateLimitStatsSnapshots.totalBuckets] = stats.totalBuckets
                it[RateLimitStatsSnapshots.maxBuckets] = stats.maxBuckets
                it[RateLimitStatsSnapshots.maxPerMinute] = stats.maxPerMinute
                it[RateLimitStatsSnapshots.sampledAt] = now
            }
        }
    }

    /** 聚合 [fromMs, toMs) 范围内的分钟桶为仪表盘序列。 */
    fun summarize(fromMs: Long, toMs: Long): DashboardSummary = transaction {
        val buckets = RateLimitStatsSnapshots.selectAll().where {
            (RateLimitStatsSnapshots.bucketStartMs greaterEq fromMs) and
                (RateLimitStatsSnapshots.bucketStartMs less toMs)
        }.orderBy(RateLimitStatsSnapshots.bucketStartMs)
            .map { it.toMinuteBucket() }

        val points = mutableListOf<SummaryPoint>()
        var prev: MinuteBucket? = null
        var totalAllowed = 0L
        var totalRejected = 0L
        var peakRejectedPerMinute = 0L
        for (b in buckets) {
            val prevB = prev
            val allowedDelta = if (prevB != null) (b.allowed - prevB.allowed).coerceAtLeast(0) else 0L
            val rejectedDelta = if (prevB != null) (b.rejected - prevB.rejected).coerceAtLeast(0) else 0L
            totalAllowed += allowedDelta
            totalRejected += rejectedDelta
            if (rejectedDelta > peakRejectedPerMinute) peakRejectedPerMinute = rejectedDelta
            points += SummaryPoint(
                bucketStartMs = b.bucketStartMs,
                allowedDelta = allowedDelta,
                rejectedDelta = rejectedDelta,
                avgTotalBuckets = b.totalBuckets.toLong(),
                maxTotalBuckets = b.totalBuckets.toLong(),
                maxPerMinute = b.maxPerMinute
            )
            prev = b
        }
        DashboardSummary(
            points = points,
            totalAllowed = totalAllowed,
            totalRejected = totalRejected,
            peakRejectionsPerMinute = peakRejectedPerMinute,
            live = GlobalRateLimiter.getInstance().stats()
        )
    }

    /** 清理早于 [beforeMs] 的快照，返回删除行数。 */
    fun prune(beforeMs: Long): Int = transaction {
        RateLimitStatsSnapshots.deleteWhere { RateLimitStatsSnapshots.bucketStartMs less beforeMs }
    }

    /** 最近一次快照时间（用于前端展示数据新鲜度）。 */
    fun lastSnapshotAt(): Long? = transaction {
        RateLimitStatsSnapshots.selectAll()
            .orderBy(RateLimitStatsSnapshots.sampledAt, org.jetbrains.exposed.sql.SortOrder.DESC)
            .limit(1)
            .firstOrNull()?.let { it[RateLimitStatsSnapshots.sampledAt] }
    }

    private fun ResultRow.toMinuteBucket(): MinuteBucket = MinuteBucket(
        bucketStartMs = this[RateLimitStatsSnapshots.bucketStartMs],
        allowed = this[RateLimitStatsSnapshots.allowed],
        rejected = this[RateLimitStatsSnapshots.rejected],
        totalBuckets = this[RateLimitStatsSnapshots.totalBuckets],
        maxBuckets = this[RateLimitStatsSnapshots.maxBuckets],
        maxPerMinute = this[RateLimitStatsSnapshots.maxPerMinute],
        sampledAt = this[RateLimitStatsSnapshots.sampledAt]
    )

    private fun envInt(name: String, default: Int): Int =
        System.getenv(name)?.takeIf { it.isNotBlank() }?.toIntOrNull()
            ?: System.getProperty(name)?.takeIf(String::isNotBlank)?.toIntOrNull()
            ?: default
}
