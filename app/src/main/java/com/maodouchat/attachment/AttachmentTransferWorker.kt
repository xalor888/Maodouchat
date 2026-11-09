package com.maodouchat.attachment

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.maodouchat.MaodouchatApp
import com.maodouchat.core.model.AttachmentTransferDisposition
import com.maodouchat.domain.messaging.AttachmentTransferWorkNaming

/** M07：附件上传 Worker——只从 inputData 取参数并委托 [AttachmentTransferUseCase]，不直读全局 Application/API。 */
class AttachmentTransferWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val (messageId, ownerUserId) = AttachmentTransferWorkNaming.resolveWorkerInput(
            inputData.getString(AttachmentTransferWorkNaming.KEY_MESSAGE_ID),
            inputData.getString(AttachmentTransferWorkNaming.KEY_OWNER_USER_ID),
        ) ?: return Result.failure()
        val app = applicationContext as? MaodouchatApp ?: return Result.failure()
        return when (AttachmentTransferUseCase(app).execute(messageId, ownerUserId, runAttemptCount)) {
            AttachmentTransferDisposition.SUCCESS -> Result.success()
            AttachmentTransferDisposition.RETRY -> Result.retry()
            AttachmentTransferDisposition.FAILURE -> Result.failure()
        }
    }
}
