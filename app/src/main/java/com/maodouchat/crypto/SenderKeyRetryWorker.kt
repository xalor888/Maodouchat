package com.maodouchat.crypto

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.maodouchat.MaodouchatApp
import com.maodouchat.network.TokenManager

class SenderKeyRetryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val tokenManager = TokenManager.getInstance(applicationContext)
        if (tokenManager.getToken().isNullOrBlank() || tokenManager.getUserId().isNullOrBlank()) {
            return Result.success()
        }

        return try {
            val app = applicationContext as? MaodouchatApp ?: return Result.failure()
            app.senderKeyRetryManager.processDueTasks(limit = 10)
            Result.success()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "SenderKey background retry failed", error)
            if (runAttemptCount >= MAX_WORKER_RETRIES) Result.failure() else Result.retry()
        }
    }

    private companion object {
        const val TAG = "SenderKeyRetryWorker"
        const val MAX_WORKER_RETRIES = 4
    }
}
