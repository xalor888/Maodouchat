package com.maodouchat.server.repository

import com.maodouchat.server.common.RateLimitStats

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
