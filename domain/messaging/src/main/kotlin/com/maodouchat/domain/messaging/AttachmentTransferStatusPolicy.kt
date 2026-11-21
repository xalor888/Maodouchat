package com.maodouchat.domain.messaging

/**
 * 附件传输状态线正反映射：Room 侧 state 字符串（AttachmentTransferState 的持久化值）
 * 与领域 TransferStatus 互转。COMMITTED/CANCELLED 在仓储侧有特殊分支，反向返回 null。
 */
object AttachmentTransferStatusPolicy {
    fun transferStatusFromState(state: String): TransferStatus = when (state) {
        "PREPARING" -> TransferStatus.PREPARING
        "QUEUED", "UPLOADING" -> TransferStatus.UPLOADING
        "READY", "SENDING" -> TransferStatus.FINALIZING
        "PAUSED" -> TransferStatus.PAUSED
        "FAILED" -> TransferStatus.FAILED
        else -> TransferStatus.UPLOADING
    }

    fun transferStateFromStatus(status: TransferStatus): String? = when (status) {
        TransferStatus.PREPARING -> "PREPARING"
        TransferStatus.UPLOADING -> "UPLOADING"
        TransferStatus.PAUSED -> "PAUSED"
        TransferStatus.FINALIZING -> "READY"
        TransferStatus.FAILED -> "FAILED"
        TransferStatus.COMMITTED, TransferStatus.CANCELLED -> null
    }
}
