package com.maodouchat.server.plugins

import com.maodouchat.server.common.RateLimitStats
import com.maodouchat.server.common.RateLimitStatsProvider
import com.maodouchat.server.config.ServerConfig
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Global per-IP rate limiter using sliding-window counters.
 *
 * **Single-instance only.** The bucket map lives in-process, so each JVM enforces its own
 * budget. Behind a load balancer with N instances an attacker effectively gets N× the limit.
 * Before scaling horizontally, replace the backing store with a shared one (e.g. Redis).
 *
 * Memory is bounded by [maxBuckets] and by retaining at most [maxPerMinute] timestamps per
 * bucket. A lazy daemon [ScheduledThreadPoolExecutor] sweeps expired buckets and is explicitly
 * stopped with the owning application lifecycle.
 */
class GlobalRateLimiter(
    private val maxPerMinute: Int
) {
    private val logger = LoggerFactory.getLogger("GlobalRateLimiter")
    private val buckets = ConcurrentHashMap<String, MutableList<Long>>()

    private val allowedCount = AtomicLong(0)
    private val rejectedCount = AtomicLong(0)
    private val bucketCount = AtomicInteger(0)
    private val capacityRejections = AtomicLong(0)
    private val lastCapacitySweepAt = AtomicLong(Long.MIN_VALUE)
    private val closed = AtomicBoolean(false)

    private val maxBuckets: Int = envInt("RATE_LIMIT_MAX_BUCKETS", 100_000)
    private val cleanupIntervalSeconds: Long = envLong("RATE_LIMIT_CLEANUP_INTERVAL_SECONDS", 60L)

    /** True once the final lifecycle shutdown has run; pools use this to mint a fresh instance. */
    val isShutdown: Boolean get() = closed.get()

    @Volatile
    private var sweeper: ScheduledThreadPoolExecutor? = null
    private val activeLifecycleIds = mutableSetOf<Long>()
    private var lastLifecycleId = 0L

    internal fun start(): Long = synchronized(this) {
        check(!closed.get()) { "Rate limiter is shut down" }
        val lifecycleId = nextLifecycleId()
        activeLifecycleIds += lifecycleId
        lifecycleId
    }

    internal fun shutdown(lifecycleId: Long) {
        // 最终关闭判定必须以布尔带出 synchronized 块：return@synchronized 只结束块本身，
        // 否则非最终 lifecycle / 重复 shutdown 也会落到下面清空共享桶（限流被重置、
        // bucketCount 与 map 失步导致 maxBuckets 上限失效）。
        var executor: ScheduledThreadPoolExecutor? = null
        val isFinalShutdown = synchronized(this) {
            when {
                !activeLifecycleIds.remove(lifecycleId) || activeLifecycleIds.isNotEmpty() -> false
                !closed.compareAndSet(false, true) -> false
                else -> {
                    executor = sweeper
                    sweeper = null
                    true
                }
            }
        }
        if (!isFinalShutdown) return
        executor?.shutdownNow()
        buckets.clear()
        bucketCount.set(0)
        clearInstance(this)
    }

    /**
     * Primary API: probe the budget and return a full [RateLimitDecision] so callers can
     * forward `Retry-After` and remaining-quota hints to the client.
     */
    fun tryAcquire(ip: String, now: Long = System.currentTimeMillis()): RateLimitDecision {
        check(!closed.get()) { "Rate limiter is shut down" }
        ensureSweeper()
        // Blank / unknown client addresses share one IP-class bucket instead of a hard reject.
        // remoteHost() already falls back to "unknown"; a 429 here used to mis-kill probes
        // and clients whose address could not be parsed.
        val bucketKey = ip.trim().ifBlank { UNKNOWN_CLIENT_KEY }
        val windowStart = now - WINDOW_MS
        if (bucketCount.get() >= maxBuckets) sweepAtCapacityIfDue(now)

        var count = 0
        var oldest: Long? = null
        var allowed = false
        var capacityRejected = false
        val mapped = buckets.compute(bucketKey) { _, existing ->
            val timestamps = existing ?: if (reserveBucket()) {
                mutableListOf()
            } else {
                capacityRejected = true
                return@compute null
            }
            synchronized(timestamps) {
                timestamps.removeAll { it < windowStart }
                if (timestamps.size < maxPerMinute) {
                    timestamps.add(now)
                    allowed = true
                }
                count = timestamps.size
                oldest = if (timestamps.isEmpty()) null else timestamps.minOrNull()
            }
            timestamps
        }
        if (mapped == null) {
            rejectedCount.incrementAndGet()
            if (capacityRejected) {
                val rejectedAtCapacity = capacityRejections.incrementAndGet()
                if (rejectedAtCapacity == 1L || rejectedAtCapacity % 1_000L == 0L) {
                    logger.warn(
                        "Rate-limit bucket cap reached ({}); rejected {} new buckets",
                        maxBuckets,
                        rejectedAtCapacity
                    )
                }
            }
            return RateLimitDecision(allowed = false, retryAfterSeconds = 1, remaining = 0)
        }

        return if (allowed) {
            allowedCount.incrementAndGet()
            RateLimitDecision(
                allowed = true,
                retryAfterSeconds = null,
                remaining = (maxPerMinute - count).coerceAtLeast(0)
            )
        } else {
            rejectedCount.incrementAndGet()
            RateLimitDecision(
                allowed = false,
                retryAfterSeconds = retryAfterSeconds(oldest, now),
                remaining = 0
            )
        }
    }

    private fun retryAfterSeconds(oldest: Long?, now: Long): Long {
        val ms = oldest?.let { it + WINDOW_MS - now }?.coerceAtLeast(1L) ?: 1L
        return ((ms + 999) / 1000).coerceAtLeast(1L)
    }

    /** Boolean convenience for callers that don't need Retry-After. */
    fun acquire(ip: String, now: Long = System.currentTimeMillis()): Boolean = tryAcquire(ip, now).allowed

    /** Alias of [acquire] matching the classic allow/tryAcquire boolean style. */
    fun allow(ip: String, now: Long = System.currentTimeMillis()): Boolean = acquire(ip, now)

    /** Observability snapshot for /health/metrics and the admin minute-bucket sampler. */
    fun stats(): RateLimitStats = RateLimitPools.combinedStatsFor(this) ?: snapshot()

    internal fun snapshot(): RateLimitStats = RateLimitStats(
        allowed = allowedCount.get(),
        rejected = rejectedCount.get(),
        totalBuckets = buckets.size,
        maxBuckets = maxBuckets,
        maxPerMinute = maxPerMinute
    )

    /** Remove buckets whose entire window has elapsed. Safe against concurrent mutation. */
    private fun sweep(now: Long) {
        val windowStart = now - WINDOW_MS
        buckets.keys.forEach { key ->
            buckets.computeIfPresent(key) { _, timestamps ->
                val expired = synchronized(timestamps) {
                    timestamps.isEmpty() || timestamps.all { it < windowStart }
                }
                if (expired) {
                    bucketCount.decrementAndGet()
                    null
                } else {
                    timestamps
                }
            }
        }
    }

    private fun reserveBucket(): Boolean {
        while (true) {
            val current = bucketCount.get()
            if (current >= maxBuckets) return false
            if (bucketCount.compareAndSet(current, current + 1)) return true
        }
    }

    private fun sweepAtCapacityIfDue(now: Long) {
        while (true) {
            val previous = lastCapacitySweepAt.get()
            // Clock going backwards must not skip the sweep (negative delta fails the 0 until INTERVAL check).
            if (previous != Long.MIN_VALUE && now >= previous && now - previous < CAPACITY_SWEEP_MIN_INTERVAL_MS) return
            if (lastCapacitySweepAt.compareAndSet(previous, now)) {
                sweep(now)
                return
            }
        }
    }

    private fun ensureSweeper() {
        if (closed.get()) return
        if (sweeper == null) {
            synchronized(this) {
                if (sweeper == null && !closed.get()) {
                    val exec = ScheduledThreadPoolExecutor(1, SweeperThreadFactory)
                    exec.removeOnCancelPolicy = true
                    exec.scheduleAtFixedRate({
                        runCatching { sweep(System.currentTimeMillis()) }
                            .onFailure { e -> logger.warn("Rate-limit sweep failed", e) }
                    }, cleanupIntervalSeconds, cleanupIntervalSeconds, TimeUnit.SECONDS)
                    sweeper = exec
                }
            }
        }
    }

    companion object {
        private const val CAPACITY_SWEEP_MIN_INTERVAL_MS = 1_000L
        private const val WINDOW_MS = 60_000L
        private const val UNKNOWN_CLIENT_KEY = "unknown"

        @Volatile
        private var instance: GlobalRateLimiter? = null

        /**
         * Observability handle used by `/health/metrics` and [com.maodouchat.server.repository.RateLimitStatsRepository].
         *
         * After the 9.4xx IP/user pool split, the intercept no longer calls this singleton.
         * Prefer the live IP pool (whose [stats] also fold in the user pool) so dashboard
         * minute buckets track real traffic instead of a leftover unused singleton.
         * Fall back to a lazy companion instance only before [configureRateLimit] has
         * started the pools (sampler initial delay is 60s).
         *
         * Counters are process-lifetime cumulative. RateLimitStatsRepository.summarize
         * diffs adjacent snapshots, so the first stored minute-bucket's delta is still 0
         * (no previous row). That is repository math; this file cannot rewrite it. The
         * production "first bucket is always empty" bug was the dead singleton.
         */
        fun getInstance(): GlobalRateLimiter {
            RateLimitPools.ipOrNull()?.let { return it }
            return instance ?: synchronized(this) {
                RateLimitPools.ipOrNull()?.let { return it }
                instance ?: GlobalRateLimiter(ServerConfig.globalRateLimitPerMinute).also { instance = it }
            }
        }

        internal fun acquireLifecycle(): Pair<GlobalRateLimiter, Long> = synchronized(this) {
            val limiter = RateLimitPools.ipOrNull()
                ?: instance?.takeUnless { it.closed.get() }
                ?: GlobalRateLimiter(ServerConfig.globalRateLimitPerMinute).also { instance = it }
            limiter to limiter.start()
        }

        internal fun bindInstance(limiter: GlobalRateLimiter) {
            synchronized(this) {
                instance = limiter
            }
        }

        private fun clearInstance(limiter: GlobalRateLimiter) {
            synchronized(this) {
                if (instance === limiter) instance = null
            }
        }

        init {
            RateLimitStatsProvider.register { getInstance().stats() }
        }
    }

    private fun nextLifecycleId(): Long {
        do {
            lastLifecycleId = if (lastLifecycleId == Long.MAX_VALUE) 1L else lastLifecycleId + 1L
        } while (lastLifecycleId in activeLifecycleIds)
        return lastLifecycleId
    }
}

/** Daemon thread factory so the sweeper never blocks JVM shutdown. */
private object SweeperThreadFactory : ThreadFactory {
    override fun newThread(r: Runnable): Thread =
        Thread(r, "rate-limit-sweeper").apply { isDaemon = true }
}
