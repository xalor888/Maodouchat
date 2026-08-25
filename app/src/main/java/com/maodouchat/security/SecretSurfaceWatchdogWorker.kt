package com.maodouchat.security

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.maodouchat.MaodouchatApp
import com.maodouchat.network.TokenManager
import java.util.concurrent.TimeUnit

/**
 * B2 密聊防泄漏看门狗（surface #74 simz / #71 ttlz）。
 *
 * 周期任务（15 分钟，与 SenderKeyRetryWorker 同模式，进程重启自动恢复）：
 * - SIM 变更防护：首次运行登记 SIM 基线，之后比对；变更时回调触发
 *   [SecretChatSession.clearAllSurfaces] 清除全部密聊本地数据（防换卡盗用）。
 * - 密聊 TTL 清扫：按 chats.chatType=SECRET 的会话，用 secret_chats.lastActivityAt 清本地解密缓存。
 *
 * 接线：MainActivity.onCreate 调用 [schedule]（幂等）。
 */
class SecretSurfaceWatchdogWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? MaodouchatApp ?: return Result.failure()
        val ownerId = TokenManager.getInstance(app).getUserId()
        if (ownerId.isNullOrBlank()) return Result.success()

        var simChanged = false
        SimChangeWatcher(app) { simChanged = true }.checkNow()
        if (simChanged) {
            SecretChatSession.clearAllSurfaces(app)
        }

        // 密聊无活动 TTL：只扫 chatType=SECRET。secret_chats 只是心跳，不是开关。
        try {
            val secretIds = app.database.chatDao().listSecretChatIds().toSet()
            if (secretIds.isNotEmpty()) {
                val activity = app.database.secretChatDao().listActivity()
                    .filter { it.chatId in secretIds }
                    .associate { it.chatId to it.lastActivityAt }
                    .toMutableMap()
                for (id in secretIds) {
                    if (id !in activity) activity[id] = 0L
                }
                val swept = SecretSessionTtl.sweepExpired(app, activity)
                if (swept.isNotEmpty()) {
                    Log.i(TAG, "Secret session TTL sweep: destroyed ${swept.size} chats")
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Secret session TTL sweep failed", error)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "secret_surface_watchdog"
        private const val TAG = "SecretSurfaceWatchdog"
        private const val PERIOD_MINUTES = 15L

        /** 幂等注册周期任务（8.48：KEEP 与 BacklogSyncWorker 一致——UPDATE 每次 onCreate 重置 15 分钟周期）。 */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SecretSurfaceWatchdogWorker>(
                PERIOD_MINUTES, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
