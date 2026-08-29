package com.maodouchat.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messaging_v2_inbox",
    indices = [
        Index(value = ["ownerUserId", "deviceId", "sequence"], unique = true),
        Index(value = ["ownerUserId", "deviceId", "state", "nextAttemptAt", "sequence"]),
        Index("messageId"),
    ],
)
data class MessagingV2InboxEntity(
    @PrimaryKey val envelopeId: String,
    val ownerUserId: String,
    val deviceId: Int,
    val sequence: Long,
    val messageId: String,
    val conversationId: String,
    val senderUserId: String,
    val senderDeviceId: Int,
    val kind: String,
    val groupRevision: Long? = null,
    val clientTimestamp: Long,
    val serverTimestamp: Long,
    val ciphertextType: String,
    val ciphertext: String,
    /**
     * Decrypted plaintext captured right after a successful ratchet step and cleared on
     * acknowledgement. Survives process death between decrypt and timeline commit so a
     * replayed envelope is projected from the journal instead of being acknowledged as a
     * libsignal Duplicate without ever reaching the timeline.
     */
    @ColumnInfo(defaultValue = "")
    val plaintextJournal: String = "",
    val state: String = MessagingV2InboxState.RECEIVED,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0L,
    val lastErrorCode: String? = null,
    val receivedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

object MessagingV2InboxState {
    const val RECEIVED = "RECEIVED"
    const val PROCESSING = "PROCESSING"
    const val ACK_PENDING = "ACK_PENDING"
    const val DEAD_LETTER_ACK_PENDING = "DEAD_LETTER_ACK_PENDING"
    const val DEAD_LETTER = "DEAD_LETTER"
    const val FAILED = "FAILED"
}
