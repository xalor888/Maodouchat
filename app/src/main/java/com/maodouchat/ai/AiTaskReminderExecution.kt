package com.maodouchat.ai

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import com.maodouchat.MaodouchatApp
import com.maodouchat.network.TokenManager
import com.maodouchat.notification.ReminderNotificationService
import com.maodouchat.security.BackgroundSessionGate

// 任务提醒执行簇：Worker 主逻辑（门禁校验 → 到期判断 → 通知展示/延迟重排/重试退避）。
internal object AiTaskReminderExecution {

    fun execute(
        inputData: Data,
        applicationContext: Context,
        runAttemptCount: Int
    ): CoroutineWorker.Result {
        val taskId = inputData.getString(AiTaskReminderWorkNaming.KEY_TASK_ID)
            ?.takeIf(String::isNotBlank)
            ?: return CoroutineWorker.Result.failure()
        val expectedUserId = inputData.getString(AiTaskReminderWorkNaming.KEY_OWNER_USER_ID)
            ?.takeIf(String::isNotBlank)
            ?: return CoroutineWorker.Result.success()
        if (!AiTaskReminderPreferences.remindersAllowed(applicationContext)) {
            return CoroutineWorker.Result.success()
        }
        val tokenManager = TokenManager.getInstance(applicationContext)
        if (tokenManager.getUserId().orEmpty() != expectedUserId || tokenManager.getToken().isNullOrBlank()) {
            return CoroutineWorker.Result.success()
        }

        return try {
            val app = applicationContext as MaodouchatApp
            val dao = app.database.aiTaskDao()
            val task = dao.getById(taskId) ?: return CoroutineWorker.Result.success()
            val dueAt = task.dueAt ?: return CoroutineWorker.Result.success()
            if (task.isCompleted || task.remindedAt != null) return CoroutineWorker.Result.success()

            val now = System.currentTimeMillis()
            if (dueAt > now) {
                AiTaskReminderTaskScheduling.deferTask(applicationContext, task.id, expectedUserId, dueAt)
                return CoroutineWorker.Result.success()
            }
            AiTaskReminderPreferences.nextAllowedTime(applicationContext, now)?.let { nextAllowed ->
                AiTaskReminderTaskScheduling.deferTask(applicationContext, task.id, expectedUserId, nextAllowed)
                return CoroutineWorker.Result.success()
            }

            // Room 读之后换号/登出：不能在下一个账号名下通知或打标记。
            if (!BackgroundSessionGate.mayContinue(expectedUserId = expectedUserId)) {
                return CoroutineWorker.Result.success()
            }
            val posted = ReminderNotificationService.showAiTaskReminder(
                context = applicationContext,
                taskId = task.id,
                chatId = task.chatId,
                taskTitle = task.title,
                dueAt = dueAt,
                showPreview = AiTaskReminderPreferences.previewEnabled(applicationContext),
                soundEnabled = AiTaskReminderPreferences.soundEnabled(applicationContext),
                expectedUserId = expectedUserId,
            )
            if (posted && BackgroundSessionGate.mayContinue(expectedUserId = expectedUserId)) {
                dao.markReminded(task.id, now)
            } else if (!posted) {
                // 通知没展示（权限被撤/失败）就 15 分钟后重排、限次数；否则任务会 pending 到 6 小时周期对账才动。
                val retries = inputData.getInt(AiTaskReminderWorkNaming.KEY_NOTIFY_RETRY_COUNT, 0)
                if (retries < MAX_NOTIFY_RETRIES) {
                    AiTaskReminderTaskScheduling.deferTask(
                        applicationContext,
                        task.id,
                        expectedUserId,
                        System.currentTimeMillis() + 15L * 60L * 1000L,
                        notifyRetryCount = retries + 1
                    )
                }
            }
            CoroutineWorker.Result.success()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "AI task reminder failed", error)
            if (runAttemptCount < 3) CoroutineWorker.Result.retry() else CoroutineWorker.Result.failure()
        }
    }

    private const val TAG = "AiTaskReminderWorker"
    /** 通知没展示时最多重排次数（15 分钟一次），到上限后交给 6 小时周期对账。 */
    private const val MAX_NOTIFY_RETRIES = 3
}
