package com.maodouchat.ai

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

// 对账调度簇：6 小时周期对账 + 按需立即对账。
internal object AiTaskReminderReconcileScheduling {

    fun ensureScheduled(context: Context) {
        val appContext = context.applicationContext
        if (!AiTaskReminderPreferences.remindersAllowed(appContext)) {
            AiTaskReminderTaskScheduling.cancelAll(appContext)
            return
        }
        val request = PeriodicWorkRequestBuilder<AiTaskReminderReconcileWorker>(6, TimeUnit.HOURS)
            .addTag(AiTaskReminderWorkNaming.TAG_ALL)
            .build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            AiTaskReminderWorkNaming.UNIQUE_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        requestReconciliation(appContext, force = true)
    }

    fun requestReconciliation(context: Context, force: Boolean) {
        val appContext = context.applicationContext
        if (!AiTaskReminderPreferences.remindersAllowed(appContext)) return
        val request = OneTimeWorkRequestBuilder<AiTaskReminderReconcileWorker>()
            .setInputData(workDataOf(AiTaskReminderWorkNaming.KEY_FORCE_RECONCILE to force))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .addTag(AiTaskReminderWorkNaming.TAG_ALL)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            AiTaskReminderWorkNaming.UNIQUE_RECONCILE_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
