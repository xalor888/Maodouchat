package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.RateLimitStatsRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.get
import io.ktor.server.routing.Route
import io.ktor.server.routing.route

/** 限流仪表盘查询。 */
internal fun Route.configureAdminRateLimitDashboardRoutes(
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
        }
    }
}
