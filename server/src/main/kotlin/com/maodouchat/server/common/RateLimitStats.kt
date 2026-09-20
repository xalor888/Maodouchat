package com.maodouchat.server.common

/**
 * Snapshot of GlobalRateLimiter counters for observability endpoints.
 */
data class RateLimitStats(
    val allowed: Long,
    val rejected: Long,
    val totalBuckets: Int,
    val maxBuckets: Int,
    val maxPerMinute: Int
)
