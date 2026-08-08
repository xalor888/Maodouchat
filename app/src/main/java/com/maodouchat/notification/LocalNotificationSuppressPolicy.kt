package com.maodouchat.notification

/**
 * Pure gates for local delivery of message notifications (FCM data-only + in-app WS path).
 * Keeps DND / global-off behavior identical so foreground WS cannot bypass quiet hours.
 *
 * 勿扰计划（DND schedule）：显式开关 + 分钟级精度（0-1439）。
 * - dndEnabled=false 时恒不抑制（兼容旧版"未设置计划即关闭"语义）。
 * - 窗口按 [startMinute, endMinute) 分钟判定，支持跨天（start>end）。
 * - startMinute==endMinute 且启用时窗口为空，同样不抑制。
 */
object LocalNotificationSuppressPolicy {

    /**
     * @return true when the device must not post a message notification right now.
     * @param currentMinute 当前时刻的分钟精度（0-1439，日历 MINUTE_OF_DAY）。
     *   传入后按分钟判定 DND 窗口；为 null 时退回小时精度（兼容旧调用）。
     */
    fun shouldSuppress(
        notificationsEnabled: Boolean,
        dndStartHour: Int,
        dndEndHour: Int,
        hourOfDay: Int,
        dndRuntimeEnabled: Boolean = true,
        dndEnabled: Boolean? = null,
        startMinute: Int? = null,
        endMinute: Int? = null,
        currentMinute: Int? = null
    ): Boolean {
        if (!notificationsEnabled) return true
        if (!dndRuntimeEnabled) return false
        val minuteOfDay = currentMinute?.coerceIn(0, 1439) ?: (hourOfDay.coerceIn(0, 23) * 60)
        val start = startMinute?.coerceIn(0, 1439) ?: (dndStartHour.coerceIn(0, 23) * 60)
        val end = endMinute?.coerceIn(0, 1439) ?: (dndEndHour.coerceIn(0, 23) * 60)
        val enabled = dndEnabled ?: true
        if (!enabled) return false
        // Equal bounds = DND disabled (same as legacy FCM service).
        if (start == end) return false
        return if (start < end) {
            minuteOfDay in start until end
        } else {
            // Overnight window, e.g. 22:30 → 07:00
            minuteOfDay >= start || minuteOfDay < end
        }
    }
}
