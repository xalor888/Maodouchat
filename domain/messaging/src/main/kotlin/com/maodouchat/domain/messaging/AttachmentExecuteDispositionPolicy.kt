package com.maodouchat.domain.messaging

/** AttachmentTransferUseCase.execute() 内剩余的纯处置决策：promote 后状态分派与上传失败处置。 */
object AttachmentExecuteDispositionPolicy {

    /** promote 失败后按行状态分派：READY/SENDING 继续 finalize；PAUSED 结束；其余已传完结束、未传完重试。 */
    fun promoteDisposition(state: String?, hasCompletedUpload: Boolean): PromoteDisposition = when (state) {
        "READY", "SENDING" -> PromoteDisposition.Finalize
        "PAUSED" -> PromoteDisposition.Done
        // 仍标记为已完成上传但 promote 失败：保持失败态等待下次校准，禁止重复上传。
        else -> if (hasCompletedUpload) PromoteDisposition.Done else PromoteDisposition.Retry
    }

    /** 上传失败是否可重试：未达重试上限且错误可重试（判定见 AttachmentTransferErrorPolicy）。 */
    fun shouldRetryUploadFailure(
        runAttemptCount: Int,
        error: Throwable,
        maxRetries: Int = AttachmentFinalizeDispositionPolicy.MAX_TRANSFER_RETRIES,
    ): Boolean = runAttemptCount < maxRetries && AttachmentTransferErrorPolicy.isRetryableTransferError(error)

    /** 可重试时行是否仍处待上传态（UPLOADING/QUEUED）：是则重排回队，否则直接重试。 */
    fun shouldRequeueForRetry(state: String?): Boolean = state == "UPLOADING" || state == "QUEUED"
}

/** promote 后分派：继续 finalize、结束本轮，或重试。 */
sealed interface PromoteDisposition {
    data object Finalize : PromoteDisposition
    data object Done : PromoteDisposition
    data object Retry : PromoteDisposition
}
