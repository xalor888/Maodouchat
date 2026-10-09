package com.maodouchat.ai

import android.content.Context
import com.maodouchat.data.local.entity.AiTaskEntity

// 任务提醒调度门面：实现已按簇拆到 AiTaskReminderWorkNaming（命名/常量）、
// AiTaskReminderReconcileScheduling（周期对账）、AiTaskReminderTaskScheduling（单任务排队/取消），
// 这里只保留统一入口，全部透传，行为不变。
object AiTaskReminderScheduler {
    internal const val KEY_TASK_ID = AiTaskReminderWorkNaming.KEY_TASK_ID
    internal const val KEY_OWNER_USER_ID = AiTaskReminderWorkNaming.KEY_OWNER_USER_ID
    internal const val KEY_FORCE_RECONCILE = AiTaskReminderWorkNaming.KEY_FORCE_RECONCILE
    internal const val KEY_NOTIFY_RETRY_COUNT = AiTaskReminderWorkNaming.KEY_NOTIFY_RETRY_COUNT

    fun ensureScheduled(context: Context) =
        AiTaskReminderReconcileScheduling.ensureScheduled(context)

    fun requestReconciliation(context: Context, force: Boolean) =
        AiTaskReminderReconcileScheduling.requestReconciliation(context, force)

    fun scheduleTask(context: Context, task: AiTaskEntity, replace: Boolean = true) =
        AiTaskReminderTaskScheduling.scheduleTask(context, task, replace)

    internal fun deferTask(
        context: Context,
        taskId: String,
        ownerUserId: String,
        triggerAt: Long,
        notifyRetryCount: Int = 0
    ) = AiTaskReminderTaskScheduling.deferTask(context, taskId, ownerUserId, triggerAt, notifyRetryCount)

    fun cancelTask(context: Context, taskId: String) =
        AiTaskReminderTaskScheduling.cancelTask(context, taskId)

    fun cancelAll(context: Context) =
        AiTaskReminderTaskScheduling.cancelAll(context)
}
