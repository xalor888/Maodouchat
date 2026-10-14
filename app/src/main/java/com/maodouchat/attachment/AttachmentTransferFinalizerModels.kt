package com.maodouchat.attachment

import com.maodouchat.data.model.Message

sealed interface AttachmentFinalizeOutcome {
    data class Sent(val message: Message) : AttachmentFinalizeOutcome
    data object AlreadyClaimed : AttachmentFinalizeOutcome
    /** claim 被 SK 轮换/清 wire 打断，状态已回 READY，worker 应 retry */
    data object ClaimInvalidated : AttachmentFinalizeOutcome
    data object ReuploadRequired : AttachmentFinalizeOutcome
    data object DiscardedTerminal : AttachmentFinalizeOutcome
    /**
     * Transient network/5xx: claim released to READY, message stays SENDING.
     * Worker should Result.retry() without showing FAILED mid-flight.
     */
    data class Transient(val error: Throwable) : AttachmentFinalizeOutcome
    data class Failed(val error: Throwable) : AttachmentFinalizeOutcome
}
