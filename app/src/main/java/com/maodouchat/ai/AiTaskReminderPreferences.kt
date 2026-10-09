package com.maodouchat.ai

import android.content.Context
import com.maodouchat.notification.NotificationPreferences

// 任务提醒偏好门面：读取簇 + 写入簇的统一入口，全部透传。
object AiTaskReminderPreferences {
    const val PREFS_NAME = NotificationPreferences.PREFS_NAME
    const val KEY_TASK_REMINDERS = NotificationPreferences.KEY_TASK_REMINDERS

    fun taskRemindersEnabled(context: Context): Boolean =
        AiTaskReminderPrefsReads.taskRemindersEnabled(context)

    fun notificationsEnabled(context: Context): Boolean =
        AiTaskReminderPrefsReads.notificationsEnabled(context)

    fun soundEnabled(context: Context): Boolean =
        AiTaskReminderPrefsReads.soundEnabled(context)

    fun previewEnabled(context: Context): Boolean =
        AiTaskReminderPrefsReads.previewEnabled(context)

    fun remindersAllowed(context: Context): Boolean =
        AiTaskReminderPrefsReads.remindersAllowed(context)

    fun nextAllowedTime(context: Context, now: Long = System.currentTimeMillis()): Long? =
        AiTaskReminderPrefsReads.nextAllowedTime(context, now)

    fun setTaskRemindersEnabled(context: Context, enabled: Boolean) =
        AiTaskReminderPrefsWrites.setTaskRemindersEnabled(context, enabled)
}
