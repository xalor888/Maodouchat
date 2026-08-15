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
        val dedupKey = callId.takeIf { it.isNotBlank() }?.let { "$userId:$it" }
        if (dedupKey != null) {
            val first = attempts.firstOrNull { it.key == callId }
            val entry = dedupHitsByKey[dedupKey]
            val hits = entry?.hits ?: 0
            // 9.165：此前 MAX_DEDUP_HITS=64 且无时间窗——群通话 fan-out 每收件人一次去重，
            // 超过 64 成员的群（默认上限 200/硬上限 500）第 65+ 收件人被回落常规限流
            //（5 次/分钟），大群通话邀请后半段全部失败。上限提到群硬上限，并限定在
            // 首次尝试后 60s 的 fan-out 窗口内，防 1:1 复用 callId 的长时刷量。
            if (hits < MAX_DEDUP_HITS && first != null && now - first.timestamp < FANOUT_WINDOW_MS) {
                dedupHitsByKey[dedupKey] = DedupEntry(hits = hits + 1, lastAccess = now)
                return CallInviteRateLimitDecision(allowed = true)
            }
            // Exceeded dedup cap or window: fall through to normal rate-limit check.
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
        /** 9.165：去重上限对齐群成员硬上限（500）——大群通话 fan-out 不再被 64 截断。 */
        private const val MAX_DEDUP_HITS = 500
        /** 9.165：fan-out 去重窗口——同 callId 仅在首次尝试后 60s 内免计次。 */
        private const val FANOUT_WINDOW_MS = 60_000L

        fun isInitialInvite(type: String, groupId: String, groupInvite: Boolean): Boolean =
            type.equals("offer", ignoreCase = true) && (groupId.isBlank() || groupInvite)

        fun sessionKey(callId: String, groupId: String, toUserId: String): String =
            if (groupId.isBlank()) "${callId.ifBlank { "legacy" }}:$toUserId" else "$groupId:$callId"
    }
}
