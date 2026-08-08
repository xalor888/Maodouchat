package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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

/**
 * Snapshot of [GlobalRateLimiter] counters for observability endpoints.
 */
data class RateLimitStats(
    val allowed: Long,
    val rejected: Long,
    val totalBuckets: Int,
    val maxBuckets: Int,
    val maxPerMinute: Int
)

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
        if (ip.isBlank()) {
            rejectedCount.incrementAndGet()
            return RateLimitDecision(allowed = false, retryAfterSeconds = null, remaining = 0)
        }
        val windowStart = now - WINDOW_MS
        if (bucketCount.get() >= maxBuckets) sweepAtCapacityIfDue(now)

        var count = 0
        var oldest: Long? = null
        var allowed = false
        var capacityRejected = false
        val mapped = buckets.compute(ip) { _, existing ->
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
            return RateLimitDecision(allowed = false, retryAfterSeconds = null, remaining = 0)
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
            val retryAfter = oldest?.let { ((it + WINDOW_MS - now).coerceAtLeast(1) + 999) / 1000 }
            RateLimitDecision(allowed = false, retryAfterSeconds = retryAfter, remaining = 0)
        }
    }

    /** Boolean convenience for callers that don't need Retry-After. */
    fun acquire(ip: String, now: Long = System.currentTimeMillis()): Boolean = tryAcquire(ip, now).allowed

    /** Alias of [acquire] matching the classic allow/tryAcquire boolean style. */
    fun allow(ip: String, now: Long = System.currentTimeMillis()): Boolean = acquire(ip, now)

    /** Observability snapshot for /health/metrics. */
    fun stats(): RateLimitStats = RateLimitStats(
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
            if (previous != Long.MIN_VALUE && now - previous in 0 until CAPACITY_SWEEP_MIN_INTERVAL_MS) return
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

        @Volatile
        private var instance: GlobalRateLimiter? = null

        /** Process-wide singleton bound to [ServerConfig.globalRateLimitPerMinute]. */
        fun getInstance(): GlobalRateLimiter = instance ?: synchronized(this) {
            instance ?: GlobalRateLimiter(ServerConfig.globalRateLimitPerMinute).also { instance = it }
        }

        internal fun acquireLifecycle(): Pair<GlobalRateLimiter, Long> = synchronized(this) {
            val limiter = instance?.takeUnless { it.closed.get() }
                ?: GlobalRateLimiter(ServerConfig.globalRateLimitPerMinute).also { instance = it }
            limiter to limiter.start()
        }

        private fun clearInstance(limiter: GlobalRateLimiter) {
            synchronized(this) {
                if (instance === limiter) instance = null
            }
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

// Env helpers mirror ServerConfig.env(): real env first, system property second, default last.
private fun env(name: String, default: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: System.getProperty(name)?.takeIf(String::isNotBlank)
        ?: default

private fun envInt(name: String, default: Int): Int =
    env(name, default.toString()).toIntOrNull()?.coerceAtLeast(1) ?: default

private fun envLong(name: String, default: Long): Long =
    env(name, default.toString()).toLongOrNull()?.coerceAtLeast(1L) ?: default

/**
 * Configures global per-IP rate limiting for all /api/ routes.
 * Skips attachment chunk uploads (they have their own rate limiting).
 */
fun Application.configureRateLimit() {
    if (attributes.contains(RateLimitInstalledKey)) return
    attributes.put(RateLimitInstalledKey, Unit)
    val (limiter, lifecycleId) = GlobalRateLimiter.acquireLifecycle()
    val logger = LoggerFactory.getLogger("RateLimitConfig")
    environment.monitor.subscribe(ApplicationStopped) {
        limiter.shutdown(lifecycleId)
    }

    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        // Only rate-limit API endpoints + the /ws handshake（握手在认证前要做 JWT 校验和两次
        // DB 查询，不设 IP 限流会成为未认证客户端的免费打点面）
        if (!path.startsWith("/api/") && path != "/ws") return@intercept
        // Skip attachment upload bodies only (per-user limits on session/chunk/one-shot).
        // Downloads GET /api/attachments/{id} stay under global IP limit (bandwidth DoS).
        val method = call.request.httpMethod.value
        val isAttachmentUploadBody =
            (method == "POST" || method == "PUT" || method == "PATCH") &&
                (path.startsWith("/api/attachment-uploads") ||
                    path == "/api/attachments" ||
                    path.matches(Regex("^/api/attachments/[^/]+/chunks?$")))
        if (isAttachmentUploadBody) return@intercept
        // Skip health checks
        if (path == "/api/health" || path == "/api/status") return@intercept

        val clientIp = call.remoteHost()
        if (RuntimeConfigService.isIpBlocked(clientIp)) {
            logger.warn("Blocked IP denied: {} on path: {}", clientIp, path)
            call.respond(HttpStatusCode.Forbidden, mapOf(
                "error" to "access denied"
            ))
            finish()
            return@intercept
        }
        val decision = limiter.tryAcquire(clientIp)
        if (!decision.allowed) {
            logger.warn(
                "Rate limit exceeded for IP: {} on path: {} (retryAfter={}s, remaining={})",
                clientIp, path, decision.retryAfterSeconds, decision.remaining
            )
            decision.retryAfterSeconds?.let { secs ->
                call.response.headers.append(HttpHeaders.RetryAfter, secs.toString())
            }
            call.respond(
                HttpStatusCode.TooManyRequests,
                ErrorResponse(
                    "请求过于频繁，请稍后重试",
                    code = "rate_limited",
                    retryAfterSeconds = decision.retryAfterSeconds
                )
            )
            finish()
        }
    }
}

private val RateLimitInstalledKey = AttributeKey<Unit>("MaodouchatRateLimitInstalled")
