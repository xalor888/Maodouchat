package com.maodouchat.ai

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.maodouchat.MaodouchatApp
import com.maodouchat.network.TokenManager
import com.maodouchat.util.AppNotifier

class AiTaskReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(AiTaskReminderScheduler.KEY_TASK_ID)
            ?.takeIf(String::isNotBlank)
            ?: return Result.failure()
        val expectedUserId = inputData.getString(AiTaskReminderScheduler.KEY_OWNER_USER_ID)
            ?.takeIf(String::isNotBlank)
            ?: return Result.success()
        if (!AiTaskReminderPreferences.remindersAllowed(applicationContext)) return Result.success()
        val tokenManager = TokenManager.getInstance(applicationContext)
        if (tokenManager.getUserId().orEmpty() != expectedUserId || tokenManager.getToken().isNullOrBlank()) {
            return Result.success()
        }

        return try {
            val app = applicationContext as MaodouchatApp
            val dao = app.database.aiTaskDao()
            val task = dao.getById(taskId) ?: return Result.success()
            val dueAt = task.dueAt ?: return Result.success()
            if (task.isCompleted || task.remindedAt != null) return Result.success()

            val now = System.currentTimeMillis()
            if (dueAt > now) {
                AiTaskReminderScheduler.deferTask(applicationContext, task.id, expectedUserId, dueAt)
                return Result.success()
            }
            AiTaskReminderPreferences.nextAllowedTime(applicationContext, now)?.let { nextAllowed ->
                AiTaskReminderScheduler.deferTask(applicationContext, task.id, expectedUserId, nextAllowed)
                return Result.success()
            }

            // Logout/account switch after Room read: do not notify or mark under the next owner.
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = expectedUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return Result.success()
            }
            val posted = AppNotifier.showAiTaskReminder(
                context = applicationContext,
                taskId = task.id,
                chatId = task.chatId,
                taskTitle = task.title,
                dueAt = dueAt,
                showPreview = AiTaskReminderPreferences.previewEnabled(applicationContext),
                soundEnabled = AiTaskReminderPreferences.soundEnabled(applicationContext),
                expectedUserId = expectedUserId,
            )
            if (posted && com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = expectedUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                dao.markReminded(task.id, now)
            } else if (!posted) {
                // 8.48 修复：通知未展示（权限撤/失败）时重排 15 分钟再试，限 MAX 次——
                // 此前既不 markReminded 也不 defer，任务保持 pending 直到 6h 周期 reconcile
                val retries = inputData.getInt(AiTaskReminderScheduler.KEY_NOTIFY_RETRY_COUNT, 0)
                if (retries < MAX_NOTIFY_RETRIES) {
                    AiTaskReminderScheduler.deferTask(
                        applicationContext,
                        task.id,
                        expectedUserId,
                        System.currentTimeMillis() + 15L * 60L * 1000L,
                        notifyRetryCount = retries + 1
                    )
                }
            }
            Result.success()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "AI task reminder failed", error)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val TAG = "AiTaskReminderWorker"
        /** 8.48：通知未展示时最多重排次数（15 分钟一次），达上限后交给 6h 周期 reconcile。 */
        const val MAX_NOTIFY_RETRIES = 3
    }
}

class AiTaskReminderReconcileWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!AiTaskReminderPreferences.remindersAllowed(applicationContext)) return Result.success()
        val tokenManager = TokenManager.getInstance(applicationContext)
        val expectedUserId = tokenManager.getUserId().orEmpty()
        if (expectedUserId.isBlank() || tokenManager.getToken().isNullOrBlank()) return Result.success()
        val force = inputData.getBoolean(AiTaskReminderScheduler.KEY_FORCE_RECONCILE, false)

        return try {
            val app = applicationContext as MaodouchatApp
            app.database.aiTaskDao().getPendingReminders().forEach { task ->
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = expectedUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return Result.success()
                }
                AiTaskReminderScheduler.scheduleTask(applicationContext, task, replace = force)
            }
            Result.success()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "AI task reminder reconciliation failed", error)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val TAG = "AiTaskReconcileWorker"
    }
}
