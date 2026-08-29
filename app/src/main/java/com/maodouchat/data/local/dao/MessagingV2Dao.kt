package com.maodouchat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.maodouchat.data.local.entity.MessagingV2InboxEntity
import com.maodouchat.data.local.entity.MessageMutationTombstoneEntity
import com.maodouchat.data.local.entity.MessagingV2OutboxEntity
import com.maodouchat.data.local.entity.MessagingV2OutboxState
import com.maodouchat.data.local.entity.MessagingV2ReceiptEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessagingV2Dao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessageTombstone(tombstone: MessageMutationTombstoneEntity)

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM message_mutation_tombstones
            WHERE ownerUserId = :ownerUserId AND messageId = :messageId
        )
        """,
    )
    suspend fun isMessageTerminal(ownerUserId: String, messageId: String): Boolean

    @Query(
        """
        INSERT OR REPLACE INTO message_mutation_tombstones(
            ownerUserId, messageId, conversationId, kind, terminalAt
        )
        SELECT :ownerUserId, id, chatId, :kind, :terminalAt
        FROM messages
        WHERE chatId = :conversationId
        """,
    )
    suspend fun tombstoneConversationMessages(
        ownerUserId: String,
        conversationId: String,
        kind: String,
        terminalAt: Long,
    )

    @Query(
        """
        DELETE FROM message_mutation_tombstones
        WHERE ownerUserId = :ownerUserId AND terminalAt < :olderThan
        """,
    )
    suspend fun pruneMessageTombstones(ownerUserId: String, olderThan: Long): Int

    /** A server replay must never reset local PROCESSING/ACK_PENDING state. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertInbox(envelopes: List<MessagingV2InboxEntity>): List<Long>

    @Query(
        """
        SELECT * FROM messaging_v2_inbox
        WHERE ownerUserId = :ownerUserId
          AND deviceId = :deviceId
          AND state IN ('RECEIVED', 'FAILED')
          AND nextAttemptAt <= :now
          AND NOT EXISTS (
            SELECT 1 FROM messaging_v2_inbox AS earlier
            WHERE earlier.ownerUserId = messaging_v2_inbox.ownerUserId
              AND earlier.deviceId = messaging_v2_inbox.deviceId
              AND earlier.sequence < messaging_v2_inbox.sequence
              AND earlier.state IN ('RECEIVED', 'FAILED', 'PROCESSING')
          )
        ORDER BY sequence ASC
        LIMIT 1
        """,
    )
    suspend fun nextProcessableInbox(
        ownerUserId: String,
        deviceId: Int,
        now: Long,
    ): MessagingV2InboxEntity?

    @Query(
        """
        UPDATE messaging_v2_inbox
        SET state = 'PROCESSING', updatedAt = :now
        WHERE envelopeId = :envelopeId
          AND ownerUserId = :ownerUserId
          AND deviceId = :deviceId
          AND state IN ('RECEIVED', 'FAILED')
          AND nextAttemptAt <= :now
        """,
    )
    suspend fun claimInbox(
        envelopeId: String,
        ownerUserId: String,
        deviceId: Int,
        now: Long,
    ): Int

    /**
     * Captures decrypted plaintext while the envelope is still PROCESSING. Written before the
     * timeline commit so a crash between the persisted ratchet step and the projection can be
     * recovered on replay instead of being acknowledged as a libsignal Duplicate.
     */
    @Query(
        """
        UPDATE messaging_v2_inbox
        SET plaintextJournal = :plaintext, updatedAt = :now
        WHERE envelopeId = :envelopeId AND state = 'PROCESSING'
        """,
    )
    suspend fun writePlaintextJournal(envelopeId: String, plaintext: String, now: Long): Int

    @Query("SELECT plaintextJournal FROM messaging_v2_inbox WHERE envelopeId = :envelopeId")
    suspend fun plaintextJournal(envelopeId: String): String?

    /** True when the envelope's message already reached the timeline or a terminal tombstone. */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM message_mutation_tombstones
            WHERE ownerUserId = :ownerUserId AND messageId = :messageId
        ) OR EXISTS(SELECT 1 FROM messages WHERE id = :messageId)
        """,
    )
    suspend fun isMessageProjected(ownerUserId: String, messageId: String): Boolean

    @Query("SELECT * FROM messaging_v2_inbox WHERE envelopeId = :envelopeId")
    suspend fun getInbox(envelopeId: String): MessagingV2InboxEntity?

    @Transaction
    suspend fun claimNextInbox(
        ownerUserId: String,
        deviceId: Int,
        now: Long,
    ): MessagingV2InboxEntity? {
        val candidate = nextProcessableInbox(ownerUserId, deviceId, now) ?: return null
        if (claimInbox(candidate.envelopeId, ownerUserId, deviceId, now) != 1) return null
        return getInbox(candidate.envelopeId)
    }

    @Query(
        """
        UPDATE messaging_v2_inbox
        SET state = 'ACK_PENDING', lastErrorCode = NULL, nextAttemptAt = 0, updatedAt = :now
        WHERE envelopeId = :envelopeId AND state = 'PROCESSING'
        """,
    )
    suspend fun markInboxAckPending(envelopeId: String, now: Long): Int

    @Query(
        """
        UPDATE messaging_v2_inbox
        SET state = 'FAILED', attempts = attempts + 1, nextAttemptAt = :nextAttemptAt,
            lastErrorCode = :errorCode, updatedAt = :now
        WHERE envelopeId = :envelopeId AND state = 'PROCESSING'
        """,
    )
    suspend fun markInboxFailed(
        envelopeId: String,
        errorCode: String,
        nextAttemptAt: Long,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE messaging_v2_inbox
        SET state = 'DEAD_LETTER_ACK_PENDING', attempts = attempts + 1,
            nextAttemptAt = 0, lastErrorCode = :errorCode, updatedAt = :now
        WHERE envelopeId = :envelopeId AND state = 'PROCESSING'
        """,
    )
    suspend fun markInboxDeadLetterAckPending(
        envelopeId: String,
        errorCode: String,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE messaging_v2_inbox
        SET state = 'RECEIVED', updatedAt = :now
        WHERE ownerUserId = :ownerUserId AND deviceId = :deviceId
          AND state = 'PROCESSING' AND updatedAt < :staleBefore
        """,
    )
    suspend fun recoverStaleInboxClaims(
        ownerUserId: String,
        deviceId: Int,
        staleBefore: Long,
        now: Long,
    ): Int

    @Query(
        """
        SELECT envelopeId FROM messaging_v2_inbox
        WHERE ownerUserId = :ownerUserId AND deviceId = :deviceId
          AND state IN ('ACK_PENDING', 'DEAD_LETTER_ACK_PENDING')
        ORDER BY sequence ASC LIMIT :limit
        """,
    )
    suspend fun ackPendingIds(ownerUserId: String, deviceId: Int, limit: Int): List<String>

    @Query(
        """
        DELETE FROM messaging_v2_inbox
        WHERE ownerUserId = :ownerUserId AND deviceId = :deviceId
          AND state = 'ACK_PENDING' AND envelopeId IN (:envelopeIds)
        """,
    )
    suspend fun deleteAcknowledgedInbox(
        ownerUserId: String,
        deviceId: Int,
        envelopeIds: List<String>,
    ): Int

    @Query(
        """
        UPDATE messaging_v2_inbox
        SET state = 'DEAD_LETTER', ciphertext = '', plaintextJournal = '', updatedAt = :now
        WHERE ownerUserId = :ownerUserId AND deviceId = :deviceId
          AND state = 'DEAD_LETTER_ACK_PENDING' AND envelopeId IN (:envelopeIds)
        """,
    )
    suspend fun markDeadLettersAcknowledged(
        ownerUserId: String,
        deviceId: Int,
        envelopeIds: List<String>,
        now: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun enqueueOutbox(message: MessagingV2OutboxEntity)

    @Query(
        """
        UPDATE messaging_v2_outbox
        SET preparedEnvelopesJson = NULL, state = 'QUEUED', attempts = 0,
            nextAttemptAt = 0, lastErrorCode = NULL, updatedAt = :now
        WHERE messageId = :messageId AND ownerUserId = :ownerUserId
          AND state IN ('QUEUED', 'READY', 'RETRY_PREPARE', 'RETRY_SEND')
        """,
    )
    suspend fun retryOutbox(
        messageId: String,
        ownerUserId: String,
        now: Long,
    ): Int

    @Query(
        """
        SELECT * FROM messaging_v2_receipts
        WHERE ownerUserId = :ownerUserId AND messageId = :messageId
        ORDER BY COALESCE(readAt, deliveredAt, updatedAt) ASC
        """,
    )
    suspend fun getReceiptsForMessage(
        ownerUserId: String,
        messageId: String,
    ): List<MessagingV2ReceiptEntity>

    @Query(
        """
        SELECT * FROM messaging_v2_receipts
        WHERE ownerUserId = :ownerUserId AND conversationId = :conversationId
        ORDER BY updatedAt ASC
        """,
    )
    fun observeReceiptsForConversation(
        ownerUserId: String,
        conversationId: String,
    ): Flow<List<MessagingV2ReceiptEntity>>

    @Query(
        """
        SELECT * FROM messaging_v2_receipts
        WHERE ownerUserId = :ownerUserId AND messageId = :messageId
          AND recipientUserId = :recipientUserId
        LIMIT 1
        """,
    )
    suspend fun getReceipt(
        ownerUserId: String,
        messageId: String,
        recipientUserId: String,
    ): MessagingV2ReceiptEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReceipt(receipt: MessagingV2ReceiptEntity)

    @Query(
        """
        SELECT candidate.* FROM messaging_v2_outbox AS candidate
        WHERE candidate.ownerUserId = :ownerUserId
          AND candidate.state IN ('QUEUED', 'READY', 'RETRY_PREPARE', 'RETRY_SEND')
          AND candidate.nextAttemptAt <= :now
          AND (
            candidate.kind IN ('SENDER_KEY', 'KEY_REQUEST', 'RECEIPT')
            OR NOT EXISTS (
              SELECT 1 FROM messaging_v2_outbox AS older
              WHERE older.ownerUserId = candidate.ownerUserId
                AND older.conversationId = candidate.conversationId
                AND older.kind IN ('DATA', 'EVENT')
                AND older.state IN (
                  'QUEUED', 'PREPARING', 'READY', 'SENDING',
                  'RETRY_PREPARE', 'RETRY_SEND'
                )
                AND older.rowid < candidate.rowid
            )
          )
        ORDER BY CASE
            WHEN candidate.kind IN ('SENDER_KEY', 'KEY_REQUEST', 'RECEIPT') THEN 0
            ELSE 1
        END ASC, candidate.rowid ASC
        LIMIT 1
        """,
    )
    suspend fun nextProcessableOutbox(ownerUserId: String, now: Long): MessagingV2OutboxEntity?

    @Query(
        """
        UPDATE messaging_v2_outbox
        SET state = CASE
              WHEN state = 'PREPARING' THEN 'RETRY_PREPARE'
              ELSE 'RETRY_SEND'
            END,
            nextAttemptAt = 0,
            lastErrorCode = 'STALE_CLAIM_RECOVERED',
            updatedAt = :now
        WHERE ownerUserId = :ownerUserId
          AND state IN ('PREPARING', 'SENDING')
          AND updatedAt < :staleBefore
        """,
    )
    suspend fun recoverStaleOutboxClaims(
        ownerUserId: String,
        staleBefore: Long,
        now: Long,
    ): Int

    @Query("SELECT * FROM messaging_v2_outbox WHERE messageId = :messageId AND ownerUserId = :ownerUserId")
    suspend fun getOutbox(messageId: String, ownerUserId: String): MessagingV2OutboxEntity?

    @Query(
        """
        UPDATE messaging_v2_outbox
        SET state = :claimedState, updatedAt = :now
        WHERE messageId = :messageId AND ownerUserId = :ownerUserId
          AND state = :expectedState AND nextAttemptAt <= :now
        """,
    )
    suspend fun claimOutbox(
        messageId: String,
        ownerUserId: String,
        expectedState: String,
        claimedState: String,
        now: Long,
    ): Int

    @Transaction
    suspend fun claimNextOutbox(ownerUserId: String, now: Long): MessagingV2OutboxEntity? {
        val candidate = nextProcessableOutbox(ownerUserId, now) ?: return null
        val claimedState = when (candidate.state) {
            MessagingV2OutboxState.QUEUED,
            MessagingV2OutboxState.RETRY_PREPARE -> MessagingV2OutboxState.PREPARING
            MessagingV2OutboxState.READY,
            MessagingV2OutboxState.RETRY_SEND -> MessagingV2OutboxState.SENDING
            else -> return null
        }
        if (
            claimOutbox(
                messageId = candidate.messageId,
                ownerUserId = ownerUserId,
                expectedState = candidate.state,
                claimedState = claimedState,
                now = now,
            ) != 1
        ) return null
        return getOutbox(candidate.messageId, ownerUserId)
    }

    @Query(
        """
        UPDATE messaging_v2_outbox
        SET preparedEnvelopesJson = :envelopesJson, groupRevision = :groupRevision,
            state = 'READY', lastErrorCode = NULL, nextAttemptAt = 0, updatedAt = :now
        WHERE messageId = :messageId AND ownerUserId = :ownerUserId AND state = 'PREPARING'
        """,
    )
    suspend fun storePreparedOutbox(
        messageId: String,
        ownerUserId: String,
        envelopesJson: String,
        groupRevision: Long?,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE messaging_v2_outbox
        SET state = :retryState, attempts = attempts + 1, nextAttemptAt = :nextAttemptAt,
            lastErrorCode = :errorCode, updatedAt = :now
        WHERE messageId = :messageId AND ownerUserId = :ownerUserId
          AND state = :expectedState
        """,
    )
    suspend fun markOutboxFailed(
        messageId: String,
        ownerUserId: String,
        expectedState: String,
        retryState: String,
        errorCode: String,
        nextAttemptAt: Long,
        now: Long,
    ): Int

    @Query("DELETE FROM messaging_v2_outbox WHERE messageId = :messageId AND ownerUserId = :ownerUserId AND state = 'SENDING'")
    suspend fun completeOutbox(messageId: String, ownerUserId: String): Int

    @Query(
        """
        DELETE FROM messaging_v2_outbox
        WHERE messageId = :messageId AND ownerUserId = :ownerUserId AND state = :expectedState
        """,
    )
    suspend fun discardOutbox(
        messageId: String,
        ownerUserId: String,
        expectedState: String,
    ): Int

    @Query(
        """
        UPDATE messaging_v2_outbox
        SET preparedEnvelopesJson = NULL, groupRevision = :newRevision, state = 'QUEUED',
            nextAttemptAt = 0, lastErrorCode = NULL, updatedAt = :now
        WHERE ownerUserId = :ownerUserId AND conversationId = :conversationId
          AND kind NOT IN ('SENDER_KEY', 'KEY_REQUEST')
          AND state IN ('READY', 'RETRY_SEND')
        """,
    )
    suspend fun invalidatePreparedGroupMessages(
        ownerUserId: String,
        conversationId: String,
        newRevision: Long?,
        now: Long,
    ): Int

    @Query(
        """
        DELETE FROM messaging_v2_outbox
        WHERE ownerUserId = :ownerUserId AND conversationId = :conversationId
          AND kind IN ('SENDER_KEY', 'KEY_REQUEST')
          AND state IN ('QUEUED', 'READY', 'RETRY_PREPARE', 'RETRY_SEND')
        """,
    )
    suspend fun deleteQueuedGroupControls(ownerUserId: String, conversationId: String): Int

    @Query(
        """
        DELETE FROM messaging_v2_outbox
        WHERE ownerUserId = :ownerUserId AND conversationId = :conversationId
        """,
    )
    suspend fun deleteConversationOutbox(ownerUserId: String, conversationId: String): Int

    @Query(
        """
        DELETE FROM messaging_v2_receipts
        WHERE ownerUserId = :ownerUserId AND conversationId = :conversationId
        """,
    )
    suspend fun deleteConversationReceipts(ownerUserId: String, conversationId: String): Int

    @Query(
        """
        DELETE FROM messaging_v2_inbox
        WHERE ownerUserId = :ownerUserId AND conversationId = :conversationId
        """,
    )
    suspend fun deleteConversationInbox(ownerUserId: String, conversationId: String): Int

    @Query(
        """
        DELETE FROM messaging_v2_inbox
        WHERE ownerUserId = :ownerUserId AND conversationId = :conversationId
          AND state = 'DEAD_LETTER'
        """,
    )
    suspend fun deleteConversationDeadLetters(ownerUserId: String, conversationId: String): Int

    @Query(
        """
        UPDATE messaging_v2_inbox
        SET state = 'ACK_PENDING', ciphertext = '', plaintextJournal = '', attempts = 0,
            nextAttemptAt = 0, lastErrorCode = NULL, updatedAt = :now
        WHERE ownerUserId = :ownerUserId AND conversationId = :conversationId
          AND state != 'DEAD_LETTER'
        """,
    )
    suspend fun discardConversationInboxForAck(
        ownerUserId: String,
        conversationId: String,
        now: Long,
    ): Int

    @Transaction
    suspend fun clearConversationState(
        ownerUserId: String,
        conversationId: String,
        serverParticipantStateDeleted: Boolean,
        now: Long,
    ) {
        deleteConversationOutbox(ownerUserId, conversationId)
        deleteConversationReceipts(ownerUserId, conversationId)
        if (serverParticipantStateDeleted) {
            deleteConversationInbox(ownerUserId, conversationId)
        } else {
            deleteConversationDeadLetters(ownerUserId, conversationId)
            discardConversationInboxForAck(ownerUserId, conversationId, now)
        }
    }

    @Transaction
    suspend fun invalidateGroupEpoch(
        ownerUserId: String,
        conversationId: String,
        newRevision: Long?,
        now: Long,
    ) {
        invalidatePreparedGroupMessages(ownerUserId, conversationId, newRevision, now)
        deleteQueuedGroupControls(ownerUserId, conversationId)
    }
}
