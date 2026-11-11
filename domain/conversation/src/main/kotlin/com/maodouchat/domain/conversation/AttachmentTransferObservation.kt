package com.maodouchat.domain.conversation

/** 附件传输观察行：app 侧从 Room 实体映射而来的最小字段集。 */
data class AttachmentTransferObservation(
    val messageId: String,
    val chatId: String,
    val ownerUserId: String,
    val state: String,
    val uploadedBytes: Long,
    val cipherSize: Long,
    val lastErrorCode: String?,
)
