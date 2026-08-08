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
        // 时区/时间变化后立即用最新偏移量重新注册推送 token，避免服务端 DND 仍用旧偏移
        // 误判安静时段而漏发推送（仅 TIME/TIMEZONE 变化需要刷新偏移）。
        if (intent.action == Intent.ACTION_TIME_CHANGED ||
            intent.action == Intent.ACTION_TIMEZONE_CHANGED
        ) {
            PushRegistrationManager.refreshRegistration(context)
        }
    }
}
