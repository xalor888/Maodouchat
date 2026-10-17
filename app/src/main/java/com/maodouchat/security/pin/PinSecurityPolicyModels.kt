package com.maodouchat.security.pin

import java.util.concurrent.ConcurrentHashMap

const val DEFAULT_MAX_FAILURES = 5
const val DEFAULT_LOCKOUT_MS = 30_000L
const val DEFAULT_SESSION_TTL_MS = 5 * 60_000L

/**
 * PIN 尝试限流与阶梯退避器
 */
class RateLimiter(
    private val maxFailures: Int = DEFAULT_MAX_FAILURES,
    private val lockoutMs: Long = DEFAULT_LOCKOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val failureCounts = ConcurrentHashMap<String, Int>()
    private val lockedUntil = ConcurrentHashMap<String, Long>()

    fun isLocked(key: String): Boolean = remainingLockoutMs(key) > 0L

    fun remainingLockoutMs(key: String): Long {
        val until = lockedUntil[key] ?: return 0L
        val remaining = until - clock()
        return if (remaining > 0L) remaining else 0L
    }

    fun recordFailure(key: String): Long {
        val now = clock()
        val failures = (failureCounts[key] ?: 0) + 1
        if (failures >= maxFailures) {
            val until = now + lockoutMs
            lockedUntil[key] = until
            failureCounts.remove(key)
            return lockoutMs
        } else {
            failureCounts[key] = failures
            return 0L
        }
    }

    fun recordSuccess(key: String) {
        failureCounts.remove(key)
        lockedUntil.remove(key)
    }

    fun reset(key: String) {
        failureCounts.remove(key)
        lockedUntil.remove(key)
    }

    fun resetAll() {
        failureCounts.clear()
        lockedUntil.clear()
    }
}

/**
 * 内存级认证会话缓存（支持 TTL 自动失效与防跨账号泄露）
 */
class AuthenticatedSessionCache(
    private val ttlMs: Long = DEFAULT_SESSION_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class CacheEntry(val userId: String, val expiryMillis: Long)
    private val sessions = ConcurrentHashMap<String, CacheEntry>()

    fun markAuthenticated(targetKey: String, userId: String) {
        if (userId.isBlank()) return
        sessions[targetKey] = CacheEntry(userId, clock() + ttlMs)
    }

    fun isAuthenticated(targetKey: String, userId: String): Boolean {
        if (userId.isBlank()) return false
        val entry = sessions[targetKey] ?: return false
        if (entry.userId != userId) return false
        if (clock() >= entry.expiryMillis) {
            sessions.remove(targetKey)
            return false
        }
        return true
    }

    fun invalidate(targetKey: String) {
        sessions.remove(targetKey)
    }

    fun invalidateUser(userId: String) {
        sessions.entries.removeIf { it.value.userId == userId }
    }

    fun invalidateAll() {
        sessions.clear()
    }
}
