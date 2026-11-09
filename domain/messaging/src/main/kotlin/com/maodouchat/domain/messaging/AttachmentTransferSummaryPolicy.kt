package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Summary

// 附件传输统计聚合的输入行：只有 domain 需要的状态字符串与字节数。
data class TransferSummaryRow(
    val state: String,
    val cipherSize: Long,
    val uploadedBytes: Long,
)

// 批量取消时单条传输的处置。
enum class AttachmentTransferCancelAction {
    COUNTED_AS_HANDLED,
    CANCEL,
    SKIP,
}

// 附件传输统计聚合（纯逻辑）：状态计数与批量取消处置。
object AttachmentTransferSummaryPolicy {

    fun summarize(transfers: List<TransferSummaryRow>): Summary = Summary(
        active = transfers.count { it.state == STATE_UPLOADING || it.state == STATE_SENDING || it.state == STATE_PREPARING },
        queued = transfers.count { it.state == STATE_QUEUED || it.state == STATE_PAUSED },
        failed = transfers.count { it.state == STATE_FAILED },
        completed = transfers.count { it.state == STATE_READY },
        totalBytes = transfers.sumOf { it.cipherSize },
        uploadedBytes = transfers.sumOf { it.uploadedBytes },
    )

    fun cancelAction(state: String): AttachmentTransferCancelAction = when (state) {
        // finalize 在途：不中断，但批量取消语义视为已处理，由 complete() 幂等收尾。
        STATE_SENDING -> AttachmentTransferCancelAction.COUNTED_AS_HANDLED
        STATE_FAILED, STATE_PAUSED, STATE_UPLOADING, STATE_QUEUED, STATE_PREPARING ->
            AttachmentTransferCancelAction.CANCEL
        else -> AttachmentTransferCancelAction.SKIP
    }

    private const val STATE_PREPARING = "PREPARING"
    private const val STATE_QUEUED = "QUEUED"
    private const val STATE_UPLOADING = "UPLOADING"
    private const val STATE_READY = "READY"
    private const val STATE_SENDING = "SENDING"
    private const val STATE_PAUSED = "PAUSED"
    private const val STATE_FAILED = "FAILED"
}
