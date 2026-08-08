package com.maodouchat.util

/**
 * 定时发送策略（纯函数）：仅本地调度，发出前可取消/改期。
 * 最短延迟 60s，最长 7 天；1:1 与群聊纯文本；每会话最多 56 条待发。
 */
object ScheduledMessagePolicy {
    const val MIN_DELAY_MS = 60_000L
    const val MAX_DELAY_MS = 7L * 24 * 60 * 60 * 1000
    const val MAX_TEXT_LENGTH = 4000
    const val MAX_PENDING_PER_CHAT = 56

    /** 快捷档位（毫秒） */
    val QUICK_DELAYS_MS = listOf(
        60_000L,                // 1 分钟
        5 * 60_000L,            // 5 分钟
        15 * 60_000L,           // 15 分钟
        30 * 60_000L,           // 30 分钟
        60 * 60_000L,           // 1 小时
        2 * 60 * 60_000L,       // 2 小时
        3 * 60 * 60_000L,       // 3 小时
        4 * 60 * 60_000L,           // 4 小时
        6 * 60 * 60_000L,       // 6 小时
        12 * 60 * 60_000L,      // 12 小时
        24 * 60 * 60_000L,      // 24 小时
        2L * 24 * 60 * 60_000L, // 2 天
        3L * 24 * 60 * 60_000L, // 3 天
        7L * 24 * 60 * 60_000L  // 7 天（上限）
    )

    fun normalizeText(raw: String?): String =
        raw.orEmpty().trim().take(MAX_TEXT_LENGTH)

    fun isValidText(raw: String?): Boolean {
        val t = normalizeText(raw)
        return t.isNotEmpty() && t.length <= MAX_TEXT_LENGTH
    }

    fun isValidSendAt(sendAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val delay = sendAtMillis - nowMillis
        return delay in MIN_DELAY_MS..MAX_DELAY_MS
    }

    fun clampSendAt(sendAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): Long {
        val min = nowMillis + MIN_DELAY_MS
        val max = nowMillis + MAX_DELAY_MS
        return sendAtMillis.coerceIn(min, max)
    }

    fun canAddMore(pendingCount: Int): Boolean =
        pendingCount < MAX_PENDING_PER_CHAT

    fun delayFromNow(sendAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): Long =
        (sendAtMillis - nowMillis).coerceAtLeast(0L)
}
