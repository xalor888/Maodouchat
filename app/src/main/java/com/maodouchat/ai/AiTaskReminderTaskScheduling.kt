package com.maodouchat.ai

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.maodouchat.data.local.entity.AiTaskEntity
import com.maodouchat.network.TokenManager
import com.maodouchat.notification.ReminderNotificationService
import java.util.concurrent.TimeUnit

// 单任务调度簇：排队 / 延迟重排 / 取消单个 / 取消全部。
internal object AiTaskReminderTaskScheduling {

    fun scheduleTask(context: Context, task: AiTaskEntity, replace: Boolean = true) {
        val ownerUserId = TokenManager.getInstance(context.applicationContext).getUserId().orEmpty()
        if (ownerUserId.isBlank()) {
            cancelTask(context, task.id)
            return
        }
        val dueAt = task.dueAt
        if (task.isCompleted || task.remindedAt != null || dueAt == null ||
            !AiTaskReminderPreferences.remindersAllowed(context)
        ) {
            cancelTask(context, task.id)
            return
        }
        enqueueTask(
            context = context,
            taskId = task.id,
            ownerUserId = ownerUserId,
            triggerAt = dueAt,
            policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        )
    }

    internal fun deferTask(
        context: Context,
        taskId: String,
        ownerUserId: String,
        triggerAt: Long,
        notifyRetryCount: Int = 0
    ) {
        if (ownerUserId.isBlank()) return
        enqueueTask(
            context = context,
            taskId = taskId,
            ownerUserId = ownerUserId,
            triggerAt = triggerAt,
            policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
            notifyRetryCount = notifyRetryCount
        )
    }

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

    private fun enqueueTask(
        context: Context,
        taskId: String,
        ownerUserId: String,
        triggerAt: Long,
        policy: ExistingWorkPolicy,
        notifyRetryCount: Int = 0
    ) {
        val appContext = context.applicationContext
        // 时钟回拨几毫秒会产生 0 延迟重排、Worker 醒来立即再 defer 成忙循环，所以延迟下限 1s。
        val delay = (triggerAt - System.currentTimeMillis()).coerceAtLeast(1_000L)
        val request = OneTimeWorkRequestBuilder<AiTaskReminderWorker>()
            .setInputData(
                workDataOf(
                    AiTaskReminderWorkNaming.KEY_TASK_ID to taskId,
                    AiTaskReminderWorkNaming.KEY_OWNER_USER_ID to ownerUserId,
                    AiTaskReminderWorkNaming.KEY_NOTIFY_RETRY_COUNT to notifyRetryCount,
                )
            )
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .addTag(AiTaskReminderWorkNaming.TAG_ALL)
            .addTag(AiTaskReminderWorkNaming.TAG_TASK)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            AiTaskReminderWorkNaming.taskWorkName(taskId), policy, request
        )
    }
}
