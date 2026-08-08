package com.maodouchat.ai

import android.content.Context
import com.maodouchat.notification.NotificationPreferences
import java.util.Calendar

object AiTaskReminderPreferences {
    const val PREFS_NAME = NotificationPreferences.PREFS_NAME
    const val KEY_TASK_REMINDERS = NotificationPreferences.KEY_TASK_REMINDERS

    fun taskRemindersEnabled(context: Context): Boolean =
        NotificationPreferences.taskRemindersEnabled(context)

    fun notificationsEnabled(context: Context): Boolean =
        NotificationPreferences.notificationsEnabled(context)

    fun soundEnabled(context: Context): Boolean =
        NotificationPreferences.soundEnabled(context)

    fun previewEnabled(context: Context): Boolean =
        NotificationPreferences.previewEnabled(context)

    fun remindersAllowed(context: Context): Boolean =
        AiTaskDndPolicy.remindersAllowed(
            taskRemindersEnabled = taskRemindersEnabled(context),
            notificationsEnabled = notificationsEnabled(context)
        )

    fun setTaskRemindersEnabled(context: Context, enabled: Boolean) {
        NotificationPreferences.setTaskRemindersEnabled(context, enabled)
    }

    /**
     * 下一允许提醒时刻。计划未启用（dndEnabled=false）或当前不在窗口内时返回 null（不推迟）。
     * 分钟级精度：窗口按 [startMinute, endMinute) 分钟判定，跨天（start>end）为夜间勿扰。
     */
    fun nextAllowedTime(context: Context, now: Long = System.currentTimeMillis()): Long? {
        if (!NotificationPreferences.dndEnabled(context)) return null
        val start = NotificationPreferences.dndStartMinute(context)
        val end = NotificationPreferences.dndEndMinute(context)
        val current = Calendar.getInstance().apply { timeInMillis = now }
        val nowMinute = current.get(Calendar.HOUR_OF_DAY) * 60 + current.get(Calendar.MINUTE)
        // 窗口判定统一走 AiTaskDndPolicy（分钟精度）；不再经 shouldSuppress 的
        // hourOfDay*60 整点换算——旧前置检查在 22:31–22:59 这类非整点时刻会漏抑制。
        val endOfWindow = (current.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, end / 60)
            set(Calendar.MINUTE, end % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        return AiTaskDndPolicy.nextAllowedTime(
            nowMs = now,
            minuteOfDay = nowMinute,
            startMinute = start,
            endMinute = end,
            endOfWindowMs = endOfWindow
        )
    }
}
