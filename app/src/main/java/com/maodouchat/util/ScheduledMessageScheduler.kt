package com.maodouchat.util

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.maodouchat.network.TokenManager
import java.util.concurrent.TimeUnit

object ScheduledMessageScheduler {
    private fun uniqueName(id: String) = "scheduled_msg_$id"

    fun schedule(context: Context, item: ScheduledMessage) {
        val ownerUserId = item.ownerUserId.ifBlank {
            TokenManager.getInstance(context.applicationContext).getUserId().orEmpty()
        }
        if (ownerUserId.isBlank()) return
        val delay = ScheduledMessagePolicy.delayFromNow(item.sendAtMillis)
        val data = Data.Builder()
            .putString(ScheduledMessageWorker.KEY_SCHEDULE_ID, item.id)
            .putString(ScheduledMessageWorker.KEY_OWNER_USER_ID, ownerUserId)
            .build()
        // 不加网络约束：离线到期时 Worker 仍会把消息转入 v2 持久发件箱，
        // 加 CONNECTED 反而会让"到点转发件箱"被无限推迟。retry() 走显式指数退避。
        val request = OneTimeWorkRequestBuilder<ScheduledMessageWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30_000L, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .addTag("scheduled_message")
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            uniqueName(item.id),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancel(context: Context, id: String) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(uniqueName(id))
    }

    fun reschedule(context: Context, item: ScheduledMessage) {
        cancel(context, item.id)
        schedule(context, item)
    }
}
