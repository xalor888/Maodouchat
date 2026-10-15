package com.maodouchat.server.repository


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

    private fun envInt(name: String, default: Int): Int =
        System.getenv(name)?.takeIf { it.isNotBlank() }?.toIntOrNull()
            ?: System.getProperty(name)?.takeIf(String::isNotBlank)?.toIntOrNull()
            ?: default
}
