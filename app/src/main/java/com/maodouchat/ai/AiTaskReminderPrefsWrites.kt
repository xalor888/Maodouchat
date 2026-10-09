package com.maodouchat.ai

import android.content.Context
import com.maodouchat.notification.NotificationPreferences

// 任务提醒偏好写入簇：目前只有任务提醒总开关。
object AiTaskReminderPrefsWrites {

    fun setTaskRemindersEnabled(context: Context, enabled: Boolean) {
        NotificationPreferences.setTaskRemindersEnabled(context, enabled)
    }
}
