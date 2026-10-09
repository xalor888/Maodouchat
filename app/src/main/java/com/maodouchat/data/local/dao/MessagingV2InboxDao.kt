package com.maodouchat.data.local.dao

import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.maodouchat.data.local.entity.MessagingV2InboxEntity
import kotlinx.coroutines.flow.Flow

interface MessagingV2InboxDao {
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

    /** M02：死信信封计数流（观测入口——poison/dead-letter 排障）。 */
    @Query("SELECT COUNT(*) FROM messaging_v2_inbox WHERE ownerUserId = :ownerUserId AND state = 'DEAD_LETTER'")
    fun observeDeadLetterCount(ownerUserId: String): Flow<Int>
}
