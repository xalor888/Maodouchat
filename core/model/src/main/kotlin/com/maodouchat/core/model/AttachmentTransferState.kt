package com.maodouchat.core.model

/** 附件传输状态字符串：与 Room 侧 AttachmentTransferEntity 的 state 取值逐字一致。 */
object AttachmentTransferState {
    const val PREPARING = "PREPARING"
    const val QUEUED = "QUEUED"
    const val UPLOADING = "UPLOADING"
    const val READY = "READY"
    const val SENDING = "SENDING"
    const val PAUSED = "PAUSED"
    const val FAILED = "FAILED"
}

/** 附件传输快照：domain 侧控制器消费的最小字段集，由 app 侧从 Room 实体映射而来。 */
data class AttachmentTransferSnapshot(
    val messageId: String,
    val state: String,
    val uploadedBytes: Long,
    val cipherSize: Long,
    val lastErrorCode: String?,
)
