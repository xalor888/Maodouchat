package com.maodouchat.ai

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import com.maodouchat.MaodouchatApp
import com.maodouchat.network.TokenManager
import com.maodouchat.security.BackgroundSessionGate

// 任务提醒对账簇：周期/按需把待提醒任务重新排进 WorkManager。
internal object AiTaskReminderReconciliation {

    fun execute(
        inputData: Data,
        applicationContext: Context,
        runAttemptCount: Int
    ): CoroutineWorker.Result {
        if (!AiTaskReminderPreferences.remindersAllowed(applicationContext)) {
            return CoroutineWorker.Result.success()
        }
        val tokenManager = TokenManager.getInstance(applicationContext)
        val expectedUserId = tokenManager.getUserId().orEmpty()
        if (expectedUserId.isBlank() || tokenManager.getToken().isNullOrBlank()) {
            return CoroutineWorker.Result.success()
        }
        val force = inputData.getBoolean(AiTaskReminderWorkNaming.KEY_FORCE_RECONCILE, false)

        return try {
            val app = applicationContext as MaodouchatApp
            app.database.aiTaskDao().getPendingReminders().forEach { task ->
                if (!BackgroundSessionGate.mayContinue(expectedUserId = expectedUserId)) {
                    return CoroutineWorker.Result.success()
                }
                AiTaskReminderTaskScheduling.scheduleTask(applicationContext, task, replace = force)
            }
            CoroutineWorker.Result.success()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "AI task reminder reconciliation failed", error)
            if (runAttemptCount < 3) CoroutineWorker.Result.retry() else CoroutineWorker.Result.failure()
        }
    }

    private const val TAG = "AiTaskReconcileWorker"
}
