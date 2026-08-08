package com.maodouchat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "attachment_transfers",
    indices = [Index("chatId"), Index("state"), Index("updatedAt")]
)
data class AttachmentTransferEntity(
    @PrimaryKey
    val messageId: String,
    val ownerUserId: String,
    val chatId: String,
    val messageType: String = "FILE",
    val sourceUri: String,
    val encryptedPath: String,
    val fileName: String,
    val mimeType: String,
    val plainSize: Long,
    val durationMs: Long? = null,
    val keyBase64: String,
    val ivBase64: String,
    val cipherSha256: String,
    val plainSha256: String,
    val cipherSize: Long,
    val attachmentId: String? = null,
    val wireContent: String? = null,
    val state: String = AttachmentTransferState.QUEUED,
    val uploadedBytes: Long = 0L,
    val attempts: Int = 0,
    val lastErrorCode: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

object AttachmentTransferState {
    const val PREPARING = "PREPARING"
    const val QUEUED = "QUEUED"
    const val UPLOADING = "UPLOADING"
    const val READY = "READY"
    const val SENDING = "SENDING"
    const val PAUSED = "PAUSED"
    const val FAILED = "FAILED"
}

fun AttachmentTransferEntity.hasCompletedUpload(): Boolean =
    !attachmentId.isNullOrBlank() && cipherSize > 0L && uploadedBytes == cipherSize

fun AttachmentTransferEntity.canRetryWithoutUpload(): Boolean =
    state in setOf(AttachmentTransferState.FAILED, AttachmentTransferState.PAUSED) && hasCompletedUpload()
