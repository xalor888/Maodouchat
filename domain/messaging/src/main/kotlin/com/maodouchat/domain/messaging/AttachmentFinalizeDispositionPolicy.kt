package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AttachmentFinalizeOutcome

/** 附件传输 finalize 结果处置（纯逻辑）：收尾 outcome → worker 下一步。 */
object AttachmentFinalizeDispositionPolicy {

    /** worker 总重试上限：6——服务端限流窗口（429 Retry-After ≤60s）内不再烧光次数直接标 FAILED。 */
    const val MAX_TRANSFER_RETRIES = 6

    /** 重试耗尽时 markFailed 用的失败码。 */
    const val FINALIZE_RETRY_EXHAUSTED_CODE = "finalize_retry_exhausted"

    fun finalizeDisposition(
        outcome: AttachmentFinalizeOutcome,
        runAttemptCount: Int,
        maxRetries: Int = MAX_TRANSFER_RETRIES,
    ): FinalizeDisposition = when (outcome) {
        is AttachmentFinalizeOutcome.Sent,
        AttachmentFinalizeOutcome.AlreadyClaimed,
        AttachmentFinalizeOutcome.DiscardedTerminal -> FinalizeDisposition.Success
        // SK 轮换清 wire / 弱网：保持 READY 或已释放 claim，worker 重试。
        AttachmentFinalizeOutcome.ClaimInvalidated,
        AttachmentFinalizeOutcome.ReuploadRequired,
        is AttachmentFinalizeOutcome.Transient -> if (runAttemptCount >= maxRetries) {
            FinalizeDisposition.Failure(markRetryExhausted = true)
        } else {
            FinalizeDisposition.Retry
        }
        is AttachmentFinalizeOutcome.Failed -> FinalizeDisposition.Failure(markRetryExhausted = false)
    }
}

/** finalize 处置结果：成功、重试，或失败（耗尽时先把行标为重试耗尽）。 */
sealed interface FinalizeDisposition {
    data object Success : FinalizeDisposition
    data object Retry : FinalizeDisposition
    data class Failure(val markRetryExhausted: Boolean) : FinalizeDisposition
}
