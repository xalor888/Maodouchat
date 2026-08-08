package com.maodouchat.server.service

import java.util.ArrayDeque
import java.util.UUID

data class CallInviteRateLimitDecision(val allowed: Boolean, val retryAfterSeconds: Long = 0)

/** In-process guard against call-spam; count sessions, not each recipient in one group call. */
class CallInviteRateLimiter(
    private val maxPerMinute: Int = 5,
    private val maxPerTenMinutes: Int = 20,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private data class Attempt(val key: String, val timestamp: Long)
    private data class DedupEntry(val hits: Int, val lastAccess: Long)
    private val attemptsByUser = mutableMapOf<String, ArrayDeque<Attempt>>()
    /** Tracks how many times a callId has been deduped; caps abuse of reused callIds. */
    private val dedupHitsByKey = mutableMapOf<String, DedupEntry>()
    private var lastSweepAt = 0L

    @Synchronized
    fun tryAcquire(userId: String, callId: String): CallInviteRateLimitDecision {
        val now = nowMillis()
        // 定期清理不再活跃用户的条目，防止内存泄漏
        if (now - lastSweepAt > SWEEP_INTERVAL_MS) {
            lastSweepAt = now
            val cutoff = now - TEN_MINUTES_MS
            attemptsByUser.entries.removeIf { (_, attempts) ->
                attempts.isEmpty() || attempts.all { it.timestamp < cutoff }
            }
            // 清理超过 10 分钟未访问的 dedup 条目，防止 callId 累积导致内存泄漏
            dedupHitsByKey.entries.removeIf { (_, entry) -> entry.lastAccess < cutoff }
        }
        val attempts = attemptsByUser.getOrPut(userId) { ArrayDeque() }
        while (attempts.isNotEmpty() && now - attempts.first.timestamp >= TEN_MINUTES_MS) attempts.removeFirst()

        // Dedup: same callId (group call fan-out to multiple recipients) is allowed without
        // counting as a new attempt, but capped to prevent callId reuse spam on 1:1 calls.
        if (callId.isNotBlank() && attempts.any { it.key == callId }) {
            val entry = dedupHitsByKey[callId]
            val hits = entry?.hits ?: 0
            if (hits < MAX_DEDUP_HITS) {
                dedupHitsByKey[callId] = DedupEntry(hits = hits + 1, lastAccess = now)
                return CallInviteRateLimitDecision(allowed = true)
            }
            // Exceeded dedup cap: fall through to normal rate-limit check.
        }

        val lastMinute = attempts.count { now - it.timestamp < ONE_MINUTE_MS }
        val blockedBy = when {
            lastMinute >= maxPerMinute -> attempts.firstOrNull { now - it.timestamp < ONE_MINUTE_MS }?.timestamp?.plus(ONE_MINUTE_MS)
            attempts.size >= maxPerTenMinutes -> attempts.first.timestamp + TEN_MINUTES_MS
            else -> null
        }
        if (blockedBy != null) {
            return CallInviteRateLimitDecision(
                allowed = false,
                retryAfterSeconds = ((blockedBy - now).coerceAtLeast(1) + 999) / 1000
            )
        }

        attempts.addLast(Attempt(callId.ifBlank { "legacy_${UUID.randomUUID()}" }, now))
        return CallInviteRateLimitDecision(allowed = true)
    }

    companion object {
        private const val ONE_MINUTE_MS = 60_000L
        private const val TEN_MINUTES_MS = 10 * ONE_MINUTE_MS
        private const val SWEEP_INTERVAL_MS = 60_000L
        /** Max dedup hits per callId: allows large group call fan-out but caps 1:1 callId reuse spam. */
        private const val MAX_DEDUP_HITS = 64

        fun isInitialInvite(type: String, groupId: String, groupInvite: Boolean): Boolean =
            type.equals("offer", ignoreCase = true) && (groupId.isBlank() || groupInvite)

        fun sessionKey(callId: String, groupId: String, toUserId: String): String =
            if (groupId.isBlank()) "${callId.ifBlank { "legacy" }}:$toUserId" else callId
    }
}
