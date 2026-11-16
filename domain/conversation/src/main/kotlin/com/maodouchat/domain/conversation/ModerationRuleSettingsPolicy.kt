package com.maodouchat.domain.conversation

// 审核规则阈值与窗口钳制：命中阈值 1~10000，窗口 1~43200 分钟（= 30 天）转毫秒
//（逐字平移自 SettingsModerationViewModel.updateRuleSettings）。
object ModerationRuleSettingsPolicy {
    const val HIT_THRESHOLD_MIN = 1
    const val HIT_THRESHOLD_MAX = 10_000
    const val WINDOW_MINUTES_MIN = 1L
    const val WINDOW_MINUTES_MAX = 43_200L
    const val MILLIS_PER_MINUTE = 60_000L

    fun clampHitThreshold(hitThreshold: Int): Int =
        hitThreshold.coerceIn(HIT_THRESHOLD_MIN, HIT_THRESHOLD_MAX)

    fun windowMs(windowMinutes: Long): Long =
        windowMinutes.coerceIn(WINDOW_MINUTES_MIN, WINDOW_MINUTES_MAX) * MILLIS_PER_MINUTE
}
