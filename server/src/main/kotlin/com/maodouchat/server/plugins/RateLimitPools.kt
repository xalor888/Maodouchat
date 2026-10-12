package com.maodouchat.server.plugins

import com.maodouchat.server.common.RateLimitStats
import com.maodouchat.server.config.ServerConfig

/**
 * Process-wide limiter pools for the two budgets. Mirrors the old singleton lifecycle so
 * repeated configure/unconfigure cycles (tests) share instances and stop sweepers exactly once.
 */
internal object RateLimitPools {
    @Volatile private var ip: GlobalRateLimiter? = null
    @Volatile private var user: GlobalRateLimiter? = null

    fun ipOrNull(): GlobalRateLimiter? = ip?.takeUnless { it.isShutdown }

    @Synchronized
    fun ipPool(): Pair<GlobalRateLimiter, Long> {
        val limiter = ip?.takeUnless { it.isShutdown } ?: GlobalRateLimiter(ServerConfig.globalRateLimitPerMinute).also {
            ip = it
            GlobalRateLimiter.bindInstance(it)
        }
        return limiter to limiter.start()
    }

    @Synchronized
    fun userPool(): Pair<GlobalRateLimiter, Long> {
        val limiter = user?.takeUnless { it.isShutdown } ?: GlobalRateLimiter(ServerConfig.authenticatedRateLimitPerMinute).also { user = it }
        return limiter to limiter.start()
    }

    fun combinedStatsFor(caller: GlobalRateLimiter): RateLimitStats? {
        val ipLimiter = ip?.takeUnless { it.isShutdown } ?: return null
        if (caller !== ipLimiter) return null
        val ipStats = ipLimiter.snapshot()
        val userStats = user?.takeUnless { it.isShutdown }?.snapshot() ?: return ipStats
        return RateLimitStats(
            allowed = ipStats.allowed + userStats.allowed,
            rejected = ipStats.rejected + userStats.rejected,
            totalBuckets = ipStats.totalBuckets + userStats.totalBuckets,
            maxBuckets = ipStats.maxBuckets + userStats.maxBuckets,
            // Keep the historical field as the unauthenticated IP budget.
            maxPerMinute = ipStats.maxPerMinute
        )
    }
}
