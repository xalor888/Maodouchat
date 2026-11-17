package com.maodouchat.ai

import android.content.Context
import com.maodouchat.domain.messaging.AiTaskDndPolicy
import com.maodouchat.domain.messaging.AiTaskReminderNextAllowedPolicy
import com.maodouchat.notification.NotificationPreferences

// 任务提醒偏好读取簇：开关读取 + 下一允许提醒时刻计算。
object AiTaskReminderPrefsReads {

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

    // 下一允许提醒时刻。计划未启用或当前不在窗口内时返回 null（不推迟）。
    fun nextAllowedTime(context: Context, now: Long = System.currentTimeMillis()): Long? =
        AiTaskReminderNextAllowedPolicy.nextAllowedTime(
            nowMs = now,
            dndEnabled = NotificationPreferences.dndEnabled(context),
            startMinute = NotificationPreferences.dndStartMinute(context),
            endMinute = NotificationPreferences.dndEndMinute(context)
        )
}
