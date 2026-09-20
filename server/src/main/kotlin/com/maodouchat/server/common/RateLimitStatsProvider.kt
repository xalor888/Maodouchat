package com.maodouchat.server.common

fun interface RateLimitStatsProvider {
    fun getStats(): RateLimitStats

    companion object {
        @Volatile
        private var defaultProvider: RateLimitStatsProvider = RateLimitStatsProvider {
            RateLimitStats(0L, 0L, 0, 0, 0)
        }

        fun get(): RateLimitStatsProvider = defaultProvider

        fun register(provider: RateLimitStatsProvider) {
            defaultProvider = provider
        }
    }
}
