package com.maodouchat.ai

/**
 * Pure DND window math for AI task reminders (W4-03).
 * 勿扰计划：显式开关（enabled） + 分钟级窗口（0-1439）。start==end 时窗口为空（不抑制）。
 */
object AiTaskDndPolicy {
    /**
     * @return next wall-clock millis when posting is allowed, or null if currently allowed.
     * @param minuteOfDay 当前时刻的当日分钟（0-1439）。
     */
    fun nextAllowedTime(
        nowMs: Long,
        minuteOfDay: Int,
        startMinute: Int,
        endMinute: Int,
        endOfWindowMs: Long
    ): Long? {
        if (!isInQuietHours(minuteOfDay, startMinute, endMinute)) return null
        return if (endOfWindowMs > nowMs) endOfWindowMs else null
    }

    fun isInQuietHours(
        minuteOfDay: Int,
        startMinute: Int,
        endMinute: Int,
        dndRuntimeEnabled: Boolean = true
    ): Boolean {
        if (!dndRuntimeEnabled) return false
        val minute = minuteOfDay.coerceIn(0, 1439)
        val start = startMinute.coerceIn(0, 1439)
        val end = endMinute.coerceIn(0, 1439)
        // Equal bounds = DND disabled（与 LocalNotificationSuppressPolicy 一致）。
        if (start == end) return false
        return if (start < end) {
            minute in start until end
        } else {
            // Overnight window，例如 22:30(1350) → 07:00(420)。
            minute >= start || minute < end
        }
    }

    fun remindersAllowed(taskRemindersEnabled: Boolean, notificationsEnabled: Boolean): Boolean =
        taskRemindersEnabled && notificationsEnabled
}
