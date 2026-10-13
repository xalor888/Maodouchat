package com.maodouchat.server.plugins

import com.maodouchat.server.repository.RateLimitStatsRepository
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import com.maodouchat.server.repository.prune
import com.maodouchat.server.repository.recordMinute

/** 限流仪表盘 + 手动采样 + 统计采样器。 */
internal fun Route.configureAdminRateLimitStatsRoutes(
    rateLimitStatsRepo: RateLimitStatsRepository,
) {
    configureAdminRateLimitDashboardRoutes(
        rateLimitStatsRepo = rateLimitStatsRepo,
    )
    configureAdminRateLimitSampleRoutes(
        rateLimitStatsRepo = rateLimitStatsRepo,
    )
}

/**
 * 限流统计采样器：每 60s 把 GlobalRateLimiter 的累计计数器写入分钟桶，
 * 同时清理超过保留期的旧桶。守护线程，不阻塞 JVM 退出。
 * 单实例部署语义与 GlobalRateLimiter 一致。
 * 返回执行器供 ApplicationStopped 关闭（8.31 运维修复：退出瞬间不再执行 DB 写）。
 */
fun startRateLimitStatsSampler(rateLimitStatsRepo: RateLimitStatsRepository): ScheduledThreadPoolExecutor {
    val exec = ScheduledThreadPoolExecutor(1, RateLimitSamplerThreadFactory)
    exec.scheduleWithFixedDelay(
        {
            runCatching {
                rateLimitStatsRepo.recordMinute()
                val cutoff = System.currentTimeMillis() - rateLimitStatsRepo.retentionDays * 86_400_000L
                rateLimitStatsRepo.prune(cutoff)
            }.onFailure { e -> samplerLogger.warn("Rate-limit stats sampling failed", e) }
        },
        SAMPLE_DELAY_MS, SAMPLE_DELAY_MS, TimeUnit.MILLISECONDS
    )
    return exec
}

@Serializable
data class RateLimitPoint(
    val bucketStartMs: Long,
    val allowed: Long,
    val rejected: Long,
    val totalBuckets: Long,
    val maxBuckets: Long,
    val maxPerMinute: Int
)

@Serializable
data class RateLimitLiveStats(
    val allowed: Long,
    val rejected: Long,
    val totalBuckets: Int,
    val maxBuckets: Int,
    val maxPerMinute: Int
)

@Serializable
data class RateLimitDashboardResponse(
    val range: String,
    val points: List<RateLimitPoint>,
    val totalAllowed: Long,
    val totalRejected: Long,
    val peakRejectionsPerMinute: Long,
    val live: RateLimitLiveStats,
    val lastSnapshotAt: Long,
    val retentionDays: Int
)

private object RateLimitSamplerThreadFactory : ThreadFactory {
    override fun newThread(r: Runnable): Thread =
        Thread(r, "rate-limit-stats-sampler").apply { isDaemon = true }
}

private val samplerLogger = LoggerFactory.getLogger("RateLimitStatsSampler")

private const val SAMPLE_DELAY_MS = 60_000L
