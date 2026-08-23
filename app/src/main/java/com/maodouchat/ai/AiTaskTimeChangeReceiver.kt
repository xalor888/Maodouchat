package com.maodouchat.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.maodouchat.push.PushRegistrationManager

class AiTaskTimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> AiTaskReminderScheduler.ensureScheduled(context)
        }
        // 时区变化后确保保活长连接仍在（FCM 已移除，不再上报时区偏移）。
        if (intent.action == Intent.ACTION_TIME_CHANGED ||
            intent.action == Intent.ACTION_TIMEZONE_CHANGED
        ) {
            PushRegistrationManager.refreshRegistration(context)
        }
    }
}
