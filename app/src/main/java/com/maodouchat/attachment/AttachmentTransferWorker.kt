package com.maodouchat.attachment

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.maodouchat.MaodouchatApp

/** M07：附件上传 Worker——只从 inputData 取参数并委托 [AttachmentTransferUseCase]，不直读全局 Application/API。 */
class AttachmentTransferWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val messageId = inputData.getString(AttachmentTransferScheduler.KEY_MESSAGE_ID)
            ?.takeIf(String::isNotBlank)
            ?: return Result.failure()
        val ownerUserId = inputData.getString(AttachmentTransferScheduler.KEY_OWNER_USER_ID)
            ?.takeIf(String::isNotBlank)
            ?: return Result.failure()
        val app = applicationContext as? MaodouchatApp ?: return Result.failure()
        return when (AttachmentTransferUseCase(app).execute(messageId, ownerUserId, runAttemptCount)) {
            AttachmentTransferDisposition.SUCCESS -> Result.success()
            AttachmentTransferDisposition.RETRY -> Result.retry()
            AttachmentTransferDisposition.FAILURE -> Result.failure()
        }
    }
}
