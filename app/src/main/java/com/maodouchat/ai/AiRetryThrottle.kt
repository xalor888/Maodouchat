package com.maodouchat.ai

import kotlinx.coroutines.delay

// 限流窗口簇：每个 chatId 的最小调用间隔 + 全局 60 分钟调用上限；进程级状态，登出/换号时清空。
internal object AiRetryThrottle {

    private val perChatLastCall = HashMap<String, Long>()
    private val globalWindow = ArrayDeque<Long>(GLOBAL_WINDOW_SIZE + 1)

    private fun nowMs(): Long = System.currentTimeMillis()

    /** 是否可以立即调用 — 返回 true 表示可以执行；false 表示必须等 [delayMs] 毫秒。 */
    @Synchronized
    fun canCallNow(chatId: String, category: AiRetryPolicy.Category): Boolean {
        val now = nowMs()
        val minInterval = when (category) {
            AiRetryPolicy.Category.LIGHT -> LIGHT_MIN_INTERVAL_MS
            AiRetryPolicy.Category.HEAVY -> HEAVY_MIN_INTERVAL_MS
        }
        val key = "$category:$chatId"
        perChatLastCall[key]?.let { last ->
            if (now - last < minInterval) return false
        }
        // 全局窗口：60 分钟内最多 200 次调用
        while (globalWindow.isNotEmpty() && now - globalWindow.first() > GLOBAL_WINDOW_MS) {
            globalWindow.removeFirst()
        }
        if (globalWindow.size >= GLOBAL_WINDOW_SIZE) return false
        return true
    }

    @Synchronized
    fun recordCall(chatId: String, category: AiRetryPolicy.Category) {
        val now = nowMs()
        if (perChatLastCall.size > MAX_TRACKED_CHAT_KEYS) {
            val cutoff = now - GLOBAL_WINDOW_MS
            perChatLastCall.entries.removeIf { it.value < cutoff }
        }
        val key = "$category:$chatId"
        perChatLastCall[key] = now
        globalWindow.addLast(now)
    }

    @Synchronized
    fun remainingDelayMs(chatId: String, category: AiRetryPolicy.Category): Long {
        val now = nowMs()
        val minInterval = when (category) {
            AiRetryPolicy.Category.LIGHT -> LIGHT_MIN_INTERVAL_MS
            AiRetryPolicy.Category.HEAVY -> HEAVY_MIN_INTERVAL_MS
        }
        val key = "$category:$chatId"
        val perChat = perChatLastCall[key]?.let { last ->
            (minInterval - (now - last)).coerceAtLeast(0L)
        } ?: 0L
        while (globalWindow.isNotEmpty() && now - globalWindow.first() > GLOBAL_WINDOW_MS) {
            globalWindow.removeFirst()
        }
        val global = if (globalWindow.size >= GLOBAL_WINDOW_SIZE) {
            val oldest = globalWindow.first()
            (GLOBAL_WINDOW_MS - (now - oldest)).coerceAtLeast(1L)
        } else {
            0L
        }
        return maxOf(perChat, global)
    }

    suspend fun awaitBackoff(chatId: String, category: AiRetryPolicy.Category) {
        val remaining = remainingDelayMs(chatId, category)
        if (remaining > 0) delay(remaining)
    }

    /** Drop process-local rate windows so logout / account switch cannot throttle the next owner. */
    @Synchronized
    fun clearSession() {
        perChatLastCall.clear()
        globalWindow.clear()
    }

    private const val LIGHT_MIN_INTERVAL_MS = 30_000L
    private const val HEAVY_MIN_INTERVAL_MS = 60_000L
    private const val GLOBAL_WINDOW_MS = 60L * 60_000L
    private const val GLOBAL_WINDOW_SIZE = 240
    private const val MAX_TRACKED_CHAT_KEYS = 2_048
}
