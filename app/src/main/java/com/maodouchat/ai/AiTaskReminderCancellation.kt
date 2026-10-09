package com.maodouchat.ai

import android.content.Context
import androidx.work.WorkManager
import com.maodouchat.notification.ReminderNotificationService

// 单任务取消簇：取消单个 / 取消全部（通知一并撤回）。
internal object AiTaskReminderCancellation {

    fun cancelTask(context: Context, taskId: String) {
        val appContext = context.applicationContext
        WorkManager.getInstance(appContext)
            .cancelUniqueWork(AiTaskReminderWorkNaming.taskWorkName(taskId))
        ReminderNotificationService.cancelAiTaskReminder(appContext, taskId)
    }

    fun cancelAll(context: Context) {
        val appContext = context.applicationContext
        val manager = WorkManager.getInstance(appContext)
        manager.cancelAllWorkByTag(AiTaskReminderWorkNaming.TAG_ALL)
        manager.cancelUniqueWork(AiTaskReminderWorkNaming.UNIQUE_PERIODIC_WORK)
        manager.cancelUniqueWork(AiTaskReminderWorkNaming.UNIQUE_RECONCILE_WORK)
        ReminderNotificationService.cancelAllAiTaskReminders(appContext)
    }
}
