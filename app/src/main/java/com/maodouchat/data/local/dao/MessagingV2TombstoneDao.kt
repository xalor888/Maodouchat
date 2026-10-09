package com.maodouchat.data.local.dao

import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.maodouchat.data.local.entity.MessageMutationTombstoneEntity

interface MessagingV2TombstoneDao {
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
}
