package com.maodouchat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messaging_v2_outbox",
    indices = [
        Index(value = ["ownerUserId", "state", "nextAttemptAt", "createdAt"]),
        Index(value = ["ownerUserId", "conversationId", "createdAt"]),
    ],
)
data class MessagingV2OutboxEntity(
    @PrimaryKey val messageId: String,
    val ownerUserId: String,
    val conversationId: String,
    val kind: String,
    /** Stored inside SQLCipher; converted to per-device ciphertext before network submission. */
    val localPayload: String,
    val clientTimestamp: Long,
    val groupRevision: Long? = null,
    val preparedEnvelopesJson: String? = null,
    val state: String = MessagingV2OutboxState.QUEUED,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0L,
    val lastErrorCode: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

object MessagingV2OutboxState {
    const val QUEUED = "QUEUED"
    const val PREPARING = "PREPARING"
    const val READY = "READY"
    const val SENDING = "SENDING"
    const val RETRY_PREPARE = "RETRY_PREPARE"
    const val RETRY_SEND = "RETRY_SEND"
}
