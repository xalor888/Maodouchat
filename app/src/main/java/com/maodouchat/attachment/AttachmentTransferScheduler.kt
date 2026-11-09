package com.maodouchat.attachment

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.maodouchat.data.local.dao.AttachmentTransferDao
import com.maodouchat.domain.messaging.AttachmentTransferWorkNaming
import com.maodouchat.network.TokenManager
import java.util.concurrent.TimeUnit

object AttachmentTransferScheduler {
    private const val TAG_LOG = "AttachmentTransferScheduler"

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedule(context: Context, messageId: String, ownerUserId: String, replace: Boolean = false) {
        require(ownerUserId.isNotBlank()) { "attachment_transfer_owner_invalid" }
        val request = OneTimeWorkRequestBuilder<AttachmentTransferWorker>()
            .setInputData(
                Data.Builder()
                    .putString(AttachmentTransferWorkNaming.KEY_MESSAGE_ID, messageId)
                    .putString(AttachmentTransferWorkNaming.KEY_OWNER_USER_ID, ownerUserId)
                    .build()
            )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(AttachmentTransferWorkNaming.TAG)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            workName(ownerUserId, messageId),
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context, messageId: String, ownerUserId: String) {
        if (ownerUserId.isBlank()) return
        // 9.302：保留轻量日志便于排查传输调度（实测发图曾被误判为卡死）
        android.util.Log.d(TAG_LOG, "cancel attachment work $messageId")
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(ownerUserId, messageId))
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(AttachmentTransferWorkNaming.TAG)
    }

    suspend fun reconcile(context: Context, dao: AttachmentTransferDao, ownerUserId: String) {
        if (ownerUserId.isBlank()) return
        val transfers = dao.getAll(ownerUserId = ownerUserId)
        val tokenManager = TokenManager.getInstance(context.applicationContext)
        if (tokenManager.getUserId().orEmpty() != ownerUserId) return
        transfers.filter { it.shouldScheduleAfterProcessDeath() }.forEach {
            if (tokenManager.getUserId().orEmpty() != ownerUserId) return
            schedule(context, it.messageId, ownerUserId)
        }
    }

    private fun workName(ownerUserId: String, messageId: String): String =
        AttachmentTransferWorkNaming.workName(ownerUserId, messageId)
}
