package com.maodouchat.server.repository

import com.maodouchat.server.db.RateLimitStatsSnapshots
import com.maodouchat.server.common.RateLimitStatsProvider
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert

/** 限流采样写路径：分钟桶记录与过期清理。 */

/** 写入/更新一个分钟桶（幂等，按 bucketStartMs 唯一）。 */
fun RateLimitStatsRepository.recordMinute(now: Long = System.currentTimeMillis()) {
    val stats = RateLimitStatsProvider.get().getStats()
    val bucketStart = now - now % 60_000L
    transaction {
        if (com.maodouchat.server.db.isH2Db()) {
            // H2 2.x 不支持 Exposed 单键 upsert 生成的 MERGE ... USING (VALUES)，用 select→update/insert
            val existing = RateLimitStatsSnapshots.selectAll()
                .where { RateLimitStatsSnapshots.bucketStartMs eq bucketStart }
                .firstOrNull()
            if (existing == null) {
                RateLimitStatsSnapshots.insert {
                    it[RateLimitStatsSnapshots.bucketStartMs] = bucketStart
                    it[RateLimitStatsSnapshots.allowed] = stats.allowed
                    it[RateLimitStatsSnapshots.rejected] = stats.rejected
                    it[RateLimitStatsSnapshots.totalBuckets] = stats.totalBuckets
                    it[RateLimitStatsSnapshots.maxBuckets] = stats.maxBuckets
                    it[RateLimitStatsSnapshots.maxPerMinute] = stats.maxPerMinute
                    it[RateLimitStatsSnapshots.sampledAt] = now
                }
            } else {
                RateLimitStatsSnapshots.update({ RateLimitStatsSnapshots.bucketStartMs eq bucketStart }) {
                    it[RateLimitStatsSnapshots.allowed] = stats.allowed
                    it[RateLimitStatsSnapshots.rejected] = stats.rejected
                    it[RateLimitStatsSnapshots.totalBuckets] = stats.totalBuckets
                    it[RateLimitStatsSnapshots.maxBuckets] = stats.maxBuckets
                    it[RateLimitStatsSnapshots.maxPerMinute] = stats.maxPerMinute
                    it[RateLimitStatsSnapshots.sampledAt] = now
                }
            }
        } else {
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
}

/** 清理早于 [beforeMs] 的快照，返回删除行数。 */
fun RateLimitStatsRepository.prune(beforeMs: Long): Int = transaction {
    RateLimitStatsSnapshots.deleteWhere { RateLimitStatsSnapshots.bucketStartMs less beforeMs }
}
