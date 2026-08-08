package com.maodouchat.notification

/**
 * 会话级免打扰时段判定（纯函数）。窗口语义与全局 DND 一致：
 * [startMinute, endMinute) 分钟判定，支持跨天（start>end）；start==end 视为关闭。
 */
object ChatQuietHoursPolicy {

    /**
     * @param window 会话免打扰窗口（enabled=false 恒不抑制）
     * @param currentMinute 当前分钟精度（0-1439，日历 MINUTE_OF_DAY）
     */
    fun shouldSuppress(
        window: ChatQuietHoursStore.QuietWindow,
        currentMinute: Int
    ): Boolean {
        if (!window.enabled) return false
        val start = window.startMinute.coerceIn(0, 1439)
        val end = window.endMinute.coerceIn(0, 1439)
        if (start == end) return false
        val minute = currentMinute.coerceIn(0, 1439)
        return if (start < end) {
            minute in start until end
        } else {
            minute >= start || minute < end
        }
    }

    /** 从日历取当前分钟。 */
    fun currentMinute(): Int {
        val cal = java.util.Calendar.getInstance()
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    }
}
