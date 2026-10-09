package com.maodouchat.ai

import android.content.Context
import com.maodouchat.data.local.entity.AiTaskEntity

// 单任务调度门面：排队簇 + 取消簇的统一入口，全部透传。
internal object AiTaskReminderTaskScheduling {

    fun scheduleTask(context: Context, task: AiTaskEntity, replace: Boolean = true) =
        AiTaskReminderSchedule.scheduleTask(context, task, replace)

    internal fun deferTask(
        context: Context,
        taskId: String,
        ownerUserId: String,
        triggerAt: Long,
        notifyRetryCount: Int = 0
    ) = AiTaskReminderSchedule.deferTask(context, taskId, ownerUserId, triggerAt, notifyRetryCount)

    fun cancelTask(context: Context, taskId: String) =
        AiTaskReminderCancellation.cancelTask(context, taskId)

    fun cancelAll(context: Context) =
        AiTaskReminderCancellation.cancelAll(context)
}
