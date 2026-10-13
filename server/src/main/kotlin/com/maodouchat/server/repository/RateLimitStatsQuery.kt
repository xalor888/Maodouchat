package com.maodouchat.server.repository

import com.maodouchat.server.db.RateLimitStatsSnapshots
import com.maodouchat.server.common.RateLimitStatsProvider
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** 限流仪表盘读路径：区间聚合与快照新鲜度。 */

/** 聚合 [fromMs, toMs) 范围内的分钟桶为仪表盘序列。 */
fun RateLimitStatsRepository.summarize(fromMs: Long, toMs: Long): RateLimitStatsRepository.DashboardSummary = transaction {
    val buckets = RateLimitStatsSnapshots.selectAll().where {
        (RateLimitStatsSnapshots.bucketStartMs greaterEq fromMs) and
            (RateLimitStatsSnapshots.bucketStartMs less toMs)
    }.orderBy(RateLimitStatsSnapshots.bucketStartMs)
        .map { it.toMinuteBucket() }

    val points = mutableListOf<RateLimitStatsRepository.SummaryPoint>()
    var prev: RateLimitStatsRepository.MinuteBucket? = null
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
        points += RateLimitStatsRepository.SummaryPoint(
            bucketStartMs = b.bucketStartMs,
            allowedDelta = allowedDelta,
            rejectedDelta = rejectedDelta,
            avgTotalBuckets = b.totalBuckets.toLong(),
            maxTotalBuckets = b.totalBuckets.toLong(),
            maxPerMinute = b.maxPerMinute
        )
        prev = b
    }
    RateLimitStatsRepository.DashboardSummary(
        points = points,
        totalAllowed = totalAllowed,
        totalRejected = totalRejected,
        peakRejectionsPerMinute = peakRejectedPerMinute,
        live = RateLimitStatsProvider.get().getStats()
    )
}

/** 最近一次快照时间（用于前端展示数据新鲜度）。 */
fun RateLimitStatsRepository.lastSnapshotAt(): Long? = transaction {
    RateLimitStatsSnapshots.selectAll()
        .orderBy(RateLimitStatsSnapshots.sampledAt, org.jetbrains.exposed.sql.SortOrder.DESC)
        .limit(1)
        .firstOrNull()?.let { it[RateLimitStatsSnapshots.sampledAt] }
}

internal fun ResultRow.toMinuteBucket(): RateLimitStatsRepository.MinuteBucket = RateLimitStatsRepository.MinuteBucket(
    bucketStartMs = this[RateLimitStatsSnapshots.bucketStartMs],
    allowed = this[RateLimitStatsSnapshots.allowed],
    rejected = this[RateLimitStatsSnapshots.rejected],
    totalBuckets = this[RateLimitStatsSnapshots.totalBuckets],
    maxBuckets = this[RateLimitStatsSnapshots.maxBuckets],
    maxPerMinute = this[RateLimitStatsSnapshots.maxPerMinute],
    sampledAt = this[RateLimitStatsSnapshots.sampledAt]
)
