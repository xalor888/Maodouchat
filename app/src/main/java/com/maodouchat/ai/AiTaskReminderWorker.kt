package com.maodouchat.ai

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

// 任务提醒 Worker：实现已拆到 AiTaskReminderExecution（执行）与
// AiTaskReminderReconciliation（对账），这里只保留 Worker 入口。
class AiTaskReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        AiTaskReminderExecution.execute(inputData, applicationContext, runAttemptCount)
}

class AiTaskReminderReconcileWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        AiTaskReminderReconciliation.execute(inputData, applicationContext, runAttemptCount)
}
