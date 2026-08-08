package com.maodouchat.crypto

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SenderKeyRetryWorkScheduler {
    private const val UNIQUE_ONE_TIME_WORK = "sender_key_retry_once"
    private const val UNIQUE_PERIODIC_WORK = "sender_key_retry_periodic"
    private const val TAG = "sender_key_retry"
    private const val MIN_DELAY_MS = 10_000L

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun ensureScheduled(context: Context) {
        val appContext = context.applicationContext
        val periodic = PeriodicWorkRequestBuilder<SenderKeyRetryWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )
    }

    fun scheduleSoon(context: Context, delayMs: Long = MIN_DELAY_MS) {
        val appContext = context.applicationContext
        val boundedDelay = delayMs.coerceAtLeast(MIN_DELAY_MS)
        val request = OneTimeWorkRequestBuilder<SenderKeyRetryWorker>()
            .setConstraints(constraints)
            .setInitialDelay(boundedDelay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, MIN_DELAY_MS, TimeUnit.MILLISECONDS)
            .addTag(TAG)
            .build()
        // KEEP 而非 REPLACE：高频触发时 REPLACE 会不断取消重排、重置延迟，导致重试饥饿；
        // 已有排队任务时保留原任务即可，15 分钟周期任务兜底。
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            UNIQUE_ONE_TIME_WORK,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun cancelAll(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        workManager.cancelUniqueWork(UNIQUE_ONE_TIME_WORK)
        workManager.cancelUniqueWork(UNIQUE_PERIODIC_WORK)
    }
}
