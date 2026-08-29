package com.maodouchat.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.maodouchat.MaodouchatApp
import com.maodouchat.network.TokenManager
import com.maodouchat.security.BackgroundSessionGate
import java.util.concurrent.TimeUnit

/**
 * Crash/restart convergence trigger for the durable v2 device inbox and outbox.
 * Message ordering, decryption, persistence, unread projection, and notifications are owned by
 * [com.maodouchat.messaging.v2.MessagingV2Runtime], not duplicated inside WorkManager.
 */
class BacklogSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? MaodouchatApp ?: return Result.failure()
        val tokenManager = TokenManager.getInstance(app)
        val ownerUserId = tokenManager.getUserId()?.takeIf(String::isNotBlank)
            ?: return Result.success()
        val token = tokenManager.getToken()?.takeIf(String::isNotBlank)
            ?: return Result.success()
        if (!BackgroundSessionGate.mayContinue(ownerUserId, token, ownerUserId)) {
            return Result.success()
        }
        runCatching { com.maodouchat.push.PushKeepAlive.ensureForUser(app) }
        return try {
            app.messagingV2Runtime.syncNow()
            Result.success()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "v2 backlog convergence failed", error)
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "maodouchat_backlog_sync"
        private const val TAG = "BacklogSync"
        private const val PERIOD_MINUTES = 15L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BacklogSyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun requestNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<BacklogSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
