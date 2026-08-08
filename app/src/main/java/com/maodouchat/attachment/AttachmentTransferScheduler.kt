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
import com.maodouchat.network.TokenManager
import java.util.concurrent.TimeUnit

object AttachmentTransferScheduler {
    const val KEY_MESSAGE_ID = "message_id"
    const val KEY_OWNER_USER_ID = "owner_user_id"
    private const val TAG = "attachment_transfer"
    private const val WORK_PREFIX = "attachment_transfer_"

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedule(context: Context, messageId: String, ownerUserId: String, replace: Boolean = false) {
        require(ownerUserId.isNotBlank()) { "attachment_transfer_owner_invalid" }
        val request = OneTimeWorkRequestBuilder<AttachmentTransferWorker>()
            .setInputData(
                Data.Builder()
                    .putString(KEY_MESSAGE_ID, messageId)
                    .putString(KEY_OWNER_USER_ID, ownerUserId)
                    .build()
            )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            workName(ownerUserId, messageId),
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context, messageId: String, ownerUserId: String) {
        if (ownerUserId.isBlank()) return
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(ownerUserId, messageId))
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(TAG)
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

    private fun workName(ownerUserId: String, messageId: String): String = WORK_PREFIX +
        ownerUserId.replace(Regex("[^A-Za-z0-9_-]"), "_") + "_" +
        messageId.replace(Regex("[^A-Za-z0-9_-]"), "_")
}
