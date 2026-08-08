package com.maodouchat.ai

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.maodouchat.data.local.entity.AiTaskEntity
import com.maodouchat.network.TokenManager
import com.maodouchat.util.AppNotifier
import java.util.concurrent.TimeUnit

object AiTaskReminderScheduler {
    private const val UNIQUE_PERIODIC_WORK = "ai_task_reminder_reconcile_periodic"
    private const val UNIQUE_RECONCILE_WORK = "ai_task_reminder_reconcile_now"
    private const val TASK_WORK_PREFIX = "ai_task_reminder_"
    private const val TAG_ALL = "ai_task_reminders"
    private const val TAG_TASK = "ai_task_reminder_task"

    internal const val KEY_TASK_ID = "task_id"
    internal const val KEY_OWNER_USER_ID = "owner_user_id"
    internal const val KEY_FORCE_RECONCILE = "force_reconcile"
    internal const val KEY_NOTIFY_RETRY_COUNT = "notify_retry_count"

    fun ensureScheduled(context: Context) {
        val appContext = context.applicationContext
        if (!AiTaskReminderPreferences.remindersAllowed(appContext)) {
            cancelAll(appContext)
            return
        }
        val request = PeriodicWorkRequestBuilder<AiTaskReminderReconcileWorker>(6, TimeUnit.HOURS)
            .addTag(TAG_ALL)
            .build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        requestReconciliation(appContext, force = true)
    }

    fun requestReconciliation(context: Context, force: Boolean) {
        val appContext = context.applicationContext
        if (!AiTaskReminderPreferences.remindersAllowed(appContext)) return
        val request = OneTimeWorkRequestBuilder<AiTaskReminderReconcileWorker>()
            .setInputData(workDataOf(KEY_FORCE_RECONCILE to force))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .addTag(TAG_ALL)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            UNIQUE_RECONCILE_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

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

    internal fun deferTask(context: Context, taskId: String, ownerUserId: String, triggerAt: Long, notifyRetryCount: Int = 0) {
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
        WorkManager.getInstance(appContext).cancelUniqueWork(taskWorkName(taskId))
        AppNotifier.cancelAiTaskReminder(appContext, taskId)
    }

    fun cancelAll(context: Context) {
        val appContext = context.applicationContext
        val manager = WorkManager.getInstance(appContext)
        manager.cancelAllWorkByTag(TAG_ALL)
        manager.cancelUniqueWork(UNIQUE_PERIODIC_WORK)
        manager.cancelUniqueWork(UNIQUE_RECONCILE_WORK)
        AppNotifier.cancelAllAiTaskReminders(appContext)
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
        // 8.48 修复：延迟加 1s 下限（对照 MessageReminderScheduler 8.53 修复）——
        // 时钟回拨几毫秒会产生 0 延迟重排，Worker 醒来立即再 defer，形成忙循环
        val delay = (triggerAt - System.currentTimeMillis()).coerceAtLeast(1_000L)
        val request = OneTimeWorkRequestBuilder<AiTaskReminderWorker>()
            .setInputData(
                workDataOf(
                    KEY_TASK_ID to taskId,
                    KEY_OWNER_USER_ID to ownerUserId,
                    KEY_NOTIFY_RETRY_COUNT to notifyRetryCount,
                )
            )
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .addTag(TAG_ALL)
            .addTag(TAG_TASK)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(taskWorkName(taskId), policy, request)
    }

    private fun taskWorkName(taskId: String): String = "$TASK_WORK_PREFIX$taskId"
}
