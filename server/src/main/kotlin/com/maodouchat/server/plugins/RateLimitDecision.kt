package com.maodouchat.server.plugins

/**
 * Result of a single rate-limit probe for a client IP.
 *
 * @property allowed true when the request fits within the per-minute budget.
 * @property retryAfterSeconds when [allowed] is false, seconds until the oldest in-window
 *           timestamp ages out and a slot may free up. null when allowed.
 * @property remaining number of requests still allowed in the current window (0 when rejected).
 */
data class RateLimitDecision(
    val allowed: Boolean,
    val retryAfterSeconds: Long?,
    val remaining: Int
)
// RateLimitStats moved to com.maodouchat.server.common.RateLimitStats
