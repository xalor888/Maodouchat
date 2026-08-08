package com.maodouchat.util

/**
 * 消息「稍后提醒」时间窗口与档位（纯函数）。
 */
object MessageReminderPolicy {
    const val MIN_DELAY_MS = 60_000L
    const val MAX_DELAY_MS = 30L * 24L * 60L * 60L * 1_000L

    /** 快捷档位（毫秒）。 */
    val QUICK_DELAYS_MS: List<Long> = listOf(
        1L * 60L * 1_000L,          // 1 分钟
        5L * 60L * 1_000L,          // 5 分钟
        15L * 60L * 1_000L,         // 15 分钟
        30L * 60L * 1_000L,         // 30 分钟
        1L * 60L * 60L * 1_000L,    // 1 小时
        2L * 60L * 60L * 1_000L,    // 2 小时
        3L * 60L * 60L * 1_000L,    // 3 小时
        4L * 60L * 60L * 1_000L,    // 4 小时
        6L * 60L * 60L * 1_000L,    // 6 小时
        12L * 60L * 60L * 1_000L,   // 12 小时
        24L * 60L * 60L * 1_000L,   // 24 小时
        2L * 24L * 60L * 60L * 1_000L,  // 2 天
        3L * 24L * 60L * 60L * 1_000L,  // 3 天
        7L * 24L * 60L * 60L * 1_000L   // 7 天
    )
}
