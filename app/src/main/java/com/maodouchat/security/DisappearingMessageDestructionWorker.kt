package com.maodouchat.security

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.maodouchat.MaodouchatApp
import com.maodouchat.network.TokenManager
import java.util.concurrent.TimeUnit

/**
 * M10: 阅后即焚消息到期持久化自毁 Worker。
 *
 * 即使应用被杀、设备重启，WorkManager 也会确保在到期时刻（或开机后立即）执行清理，
 * 彻底消除私密消息因未打开页面而永久驻留磁盘的风险。
 */
class DisappearingMessageDestructionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? MaodouchatApp ?: return Result.failure()
        val expectedOwnerId = inputData.getString(KEY_OWNER_USER_ID)
        val liveUserId = TokenManager.getInstance(app).getUserId()

        // 账号隔离校验：如果指定了 ownerUserId 且与当前登录账号不一致，则不执行清理，避免跨账号误操作
        if (!expectedOwnerId.isNullOrBlank() && liveUserId != null && expectedOwnerId != liveUserId) {
            Log.w(TAG, "Worker ownerUserId mismatch ($expectedOwnerId vs $liveUserId), skipping")
            return Result.success()
        }

        return try {
            val purged = app.secretConversationController.purgeExpiredMessages()
            if (purged.isNotEmpty()) {
                Log.i(TAG, "Disappearing messages destroyed: ${purged.size} messages")
            }
            Result.success()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Failed to purge disappearing messages", error)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "DisappearingDestruction"
        private const val WORK_PREFIX = "disappearing_destruction"
        const val KEY_CHAT_ID = "chat_id"
        const val KEY_OWNER_USER_ID = "owner_user_id"

        fun schedule(context: Context, delayMs: Long, chatId: String? = null, ownerUserId: String? = null) {
            val safeDelay = delayMs.coerceAtLeast(0L)
            val data = Data.Builder().apply {
                if (!chatId.isNullOrBlank()) putString(KEY_CHAT_ID, chatId)
                if (!ownerUserId.isNullOrBlank()) putString(KEY_OWNER_USER_ID, ownerUserId)
            }.build()

            val uniqueName = if (!chatId.isNullOrBlank()) {
                "${WORK_PREFIX}_$chatId"
            } else {
                "${WORK_PREFIX}_global"
            }

            val request = OneTimeWorkRequestBuilder<DisappearingMessageDestructionWorker>()
                .setInitialDelay(safeDelay, TimeUnit.MILLISECONDS)
                .setInputData(data)
                .addTag("disappearing_messages")
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                uniqueName,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun cancel(context: Context, chatId: String? = null) {
            val uniqueName = if (!chatId.isNullOrBlank()) {
                "${WORK_PREFIX}_$chatId"
            } else {
                "${WORK_PREFIX}_global"
            }
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(uniqueName)
        }
    }
}
