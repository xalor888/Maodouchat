package com.maodouchat.data.local.dao

import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.maodouchat.data.local.entity.MessagingV2ReceiptEntity
import kotlinx.coroutines.flow.Flow

interface MessagingV2ReceiptDao {
    @Query(
        """
        SELECT * FROM messaging_v2_receipts
        WHERE ownerUserId = :ownerUserId AND messageId = :messageId
        ORDER BY COALESCE(playedAt, readAt, deliveredAt, updatedAt) ASC
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
        DELETE FROM messaging_v2_receipts
        WHERE ownerUserId = :ownerUserId AND conversationId = :conversationId
        """,
    )
    suspend fun deleteConversationReceipts(ownerUserId: String, conversationId: String): Int
}
