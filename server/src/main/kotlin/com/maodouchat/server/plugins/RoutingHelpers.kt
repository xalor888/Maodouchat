package com.maodouchat.server.plugins

import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

/**
 * 通用辅助函数母文件：保留限流器与杂项 helper；
 * 认证/地址/账号 helper、Signal 密钥 helper 与共享常量已按域拆出（零行为改动）。
 */

// ── 限流器扩展 ────────────────────────

internal class BoundedRateLimiter(
    private val maxBuckets: Int = 10_000,
    private val windowMs: Long = 60_000L,
    private val capacitySweepIntervalMs: Long = 1_000L,
) {
    private val buckets = ConcurrentHashMap<String, MutableList<Long>>()
    private val bucketSlots = Semaphore(maxBuckets)
    private val lastCapacitySweepAt = AtomicLong(Long.MIN_VALUE)

    init {
        require(maxBuckets > 0)
        require(windowMs > 0L)
        require(capacitySweepIntervalMs > 0L)
    }

    fun acquire(
        key: String,
        maxPerMinute: Int = 10,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val normalizedKey = key.takeIf(String::isNotBlank) ?: return false
        if (maxPerMinute <= 0) return false
        val windowStart = now - windowMs
        if (bucketSlots.availablePermits() == 0) sweepAtCapacityIfDue(now, windowStart)
        var allowed = false
        buckets.compute(normalizedKey) { _, existing ->
            val timestamps = existing ?: run {
                if (!bucketSlots.tryAcquire()) return@compute null
                mutableListOf()
            }
            timestamps.removeAll { it < windowStart }
            if (timestamps.size < maxPerMinute) {
                timestamps.add(now)
                allowed = true
            }
            timestamps
        }
        return allowed
    }

    fun acquireSendCodeIp(ip: String, maxPerMinute: Int = 20): Boolean =
        acquire(ip, maxPerMinute)

    fun allowPreKeyFetch(
        requesterId: String,
        targetUserId: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean = acquire("$requesterId:$targetUserId", maxPerMinute = 10, now = now)

    fun reset(key: String): Boolean {
        val normalizedKey = key.takeIf(String::isNotBlank) ?: return false
        var removed = false
        buckets.computeIfPresent(normalizedKey) { _, _ ->
            bucketSlots.release()
            removed = true
            null
        }
        return removed
    }

    private fun sweepAtCapacityIfDue(now: Long, windowStart: Long) {
        while (true) {
            val previous = lastCapacitySweepAt.get()
            if (previous != Long.MIN_VALUE && now >= previous && now - previous < capacitySweepIntervalMs) return
            if (lastCapacitySweepAt.compareAndSet(previous, now)) break
        }
        buckets.keys.forEach { key ->
            buckets.computeIfPresent(key) { _, timestamps ->
                timestamps.removeAll { it < windowStart }
                if (timestamps.isEmpty()) {
                    bucketSlots.release()
                    null
                } else {
                    timestamps
                }
            }
        }
    }

}

// ── 杂项辅助 ──────────────────────────

internal fun hasContentModerationAccess(userRepo: com.maodouchat.server.repository.UserRepository, userId: String): Boolean =
    com.maodouchat.server.service.AdminIdentityResolver.resolve(userId, userRepo).hasContentModerationAccess

internal fun restrictionMessage(until: Long, action: String): String {
    val remainingMinutes = ((until - System.currentTimeMillis()).coerceAtLeast(0) + 59_999L) / 60_000L
    return "$action，约 ${remainingMinutes.coerceAtLeast(1)} 分钟后恢复"
}

/** 通用 {"status":"ok"} 应答。收敛各路由文件的私有 `respondOk` 拷贝。 */
internal suspend fun ApplicationCall.respondOk() {
    respond(kotlinx.serialization.json.buildJsonObject { put("status", "ok") })
}
