package com.maodouchat.data.local.dao

import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.maodouchat.data.local.entity.MessagingV2OutboxEntity
import com.maodouchat.data.local.entity.MessagingV2OutboxState
import kotlinx.coroutines.flow.Flow

interface MessagingV2OutboxDao {
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
        DELETE FROM messaging_v2_outbox
        WHERE messageId = :messageId AND ownerUserId = :ownerUserId
          AND state IN ('QUEUED', 'PREPARING', 'READY', 'RETRY_PREPARE', 'RETRY_SEND')
        """,
    )
    suspend fun cancelOutboxMessage(ownerUserId: String, messageId: String): Int

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

    /** M02：出站队列 pending 计数流（待发送/发送中），供 domain `MessagingV2Runtime` 端口。 */
    @Query(
        "SELECT COUNT(*) FROM messaging_v2_outbox WHERE ownerUserId = :ownerUserId AND state IN ('QUEUED','PREPARING','SENDING','READY')"
    )
    fun observePendingOutboxCount(ownerUserId: String): Flow<Int>

    /** M02：出站队列 retrying 计数流（退避重试），供 domain `MessagingV2Runtime` 端口。 */
    @Query(
        "SELECT COUNT(*) FROM messaging_v2_outbox WHERE ownerUserId = :ownerUserId AND state IN ('RETRY_PREPARE','RETRY_SEND')"
    )
    fun observeRetryingOutboxCount(ownerUserId: String): Flow<Int>
}
