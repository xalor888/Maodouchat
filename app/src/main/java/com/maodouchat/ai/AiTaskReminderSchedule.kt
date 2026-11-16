package com.maodouchat.ai

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.maodouchat.core.model.AiTaskReminderWorkNaming
import com.maodouchat.data.local.entity.AiTaskEntity
import com.maodouchat.domain.messaging.AiTaskReminderSchedulePolicy
import com.maodouchat.domain.messaging.WorkDelayPolicy
import com.maodouchat.network.TokenManager
import java.util.concurrent.TimeUnit

// 单任务排队簇：排队 / 延迟重排（enqueueTask 私有实现在此）。
internal object AiTaskReminderSchedule {

    fun scheduleTask(context: Context, task: AiTaskEntity, replace: Boolean = true) {
        val ownerUserId = TokenManager.getInstance(context.applicationContext).getUserId().orEmpty()
        if (ownerUserId.isBlank()) {
            AiTaskReminderCancellation.cancelTask(context, task.id)
            return
        }
        val dueAt = task.dueAt
        if (!AiTaskReminderSchedulePolicy.shouldSchedule(
                task.isCompleted, task.remindedAt, dueAt,
                AiTaskReminderPreferences.remindersAllowed(context)
            )
        ) {
            AiTaskReminderCancellation.cancelTask(context, task.id)
            return
        }
        enqueueTask(
            context = context,
            taskId = task.id,
            ownerUserId = ownerUserId,
            // 上方 shouldSchedule 已保证 dueAt 非空，空值会提前 return。
            triggerAt = dueAt!!,
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

    private fun enqueueTask(
        context: Context,
        taskId: String,
        ownerUserId: String,
        triggerAt: Long,
        policy: ExistingWorkPolicy,
        notifyRetryCount: Int = 0
    ) {
        val appContext = context.applicationContext
        val delay = WorkDelayPolicy.initialDelayUntil(triggerAt, System.currentTimeMillis())
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
