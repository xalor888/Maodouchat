package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.RateLimitStatsRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/** 限流仪表盘 + 手动采样 + 统计采样器。 */
internal fun Route.configureAdminRateLimitStatsRoutes(
    rateLimitStatsRepo: RateLimitStatsRepository,
) {
    authenticate("admin-jwt") {
        route("/api/admin") {
            get("/rate-limit/dashboard") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val range = parseRateLimitRange(call.request.queryParameters)
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("range 非法：1h / 24h / 7d"))
                val hours = when (range) {
                    "1h" -> 1
                    "24h" -> 24
                    // parse 已保证只剩 7d
                    else -> 24 * 7
                }
                val now = System.currentTimeMillis()
                val fromMs = now - hours * 3_600_000L
                val summary = rateLimitStatsRepo.summarize(fromMs, now)
                call.respond(
                    RateLimitDashboardResponse(
                        range = range,
                        points = summary.points.map { p ->
                            RateLimitPoint(
                                bucketStartMs = p.bucketStartMs,
                                allowed = p.allowedDelta,
                                rejected = p.rejectedDelta,
                                totalBuckets = p.avgTotalBuckets,
                                maxBuckets = p.maxTotalBuckets,
                                maxPerMinute = p.maxPerMinute
                            )
                        },
                        totalAllowed = summary.totalAllowed,
                        totalRejected = summary.totalRejected,
                        peakRejectionsPerMinute = summary.peakRejectionsPerMinute,
                        live = summary.live.let {
                            RateLimitLiveStats(
                                allowed = it.allowed, rejected = it.rejected,
                                totalBuckets = it.totalBuckets, maxBuckets = it.maxBuckets,
                                maxPerMinute = it.maxPerMinute
                            )
                        },
                        lastSnapshotAt = rateLimitStatsRepo.lastSnapshotAt() ?: 0L,
                        retentionDays = rateLimitStatsRepo.retentionDays
                    )
                )
            }

            post("/rate-limit/sample") {
                if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val actorId = call.requireUserId()
                rateLimitStatsRepo.recordMinute()
                recordAdminAudit(actorId, "RATE_LIMIT_MANUAL_SAMPLE", "")
                call.respond(buildJsonObject { put("ok", true) })
            }
        }
    }
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
