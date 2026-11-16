package com.maodouchat.util

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.maodouchat.core.model.MessageReminder
import com.maodouchat.domain.messaging.WorkDelayPolicy
import java.util.concurrent.TimeUnit

/**
 * 消息「稍后提醒」WorkManager 调度：每个提醒一条唯一一次性作业（进程重启自动恢复）。
 * 命名/退避/标签模式与 AiTaskReminderScheduler / ScheduledMessageScheduler 一致。
 */
object MessageReminderScheduler {

    private const val WORK_PREFIX = "message_reminder_"
    private const val TAG_ALL = "message_reminders"

    private fun workName(id: String): String = "$WORK_PREFIX$id"

    fun schedule(context: Context, reminder: MessageReminder) {
        if (reminder.id.isBlank()) return
        val delayMs = WorkDelayPolicy.initialDelayUntil(
            reminder.remindAtMillis, System.currentTimeMillis()
        )
        val request = OneTimeWorkRequestBuilder<MessageReminderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .addTag(TAG_ALL)
            .setInputData(
                androidx.work.Data.Builder()
                    .putString(MessageReminderWorker.KEY_REMINDER_ID, reminder.id)
                    .putString(MessageReminderWorker.KEY_OWNER_USER_ID, reminder.ownerUserId)
                    .build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName(reminder.id), ExistingWorkPolicy.REPLACE, request)
    }

    fun reschedule(context: Context, reminder: MessageReminder) =
        schedule(context, reminder)

    fun cancel(context: Context, id: String) {
        if (id.isBlank()) return
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG_ALL)
    }
}
