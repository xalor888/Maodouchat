package com.maodouchat.data.local.dao

import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.maodouchat.data.local.entity.MessageEntity

interface MessageMutationDao {
    // Preserve message_search_documents children. SQLite REPLACE deletes the message row
    // first, which cascades through documents and tokens on routine status/reaction edits.
    @Upsert
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageIfAbsent(message: MessageEntity): Long

    @Upsert
    suspend fun insertMessages(messages: List<MessageEntity>)

    /** Unconditional status write — prefer [updateMessageStatusIfAdvanced] for delivery receipts. */
    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: String)

    /** Persist the immutable delivery privacy mode before the transport can accept the row. */
    @Query("UPDATE messages SET sealedSender = :sealedSender WHERE id = :messageId")
    suspend fun updateMessageSealedSender(messageId: String, sealedSender: Boolean): Int

    @Query("UPDATE messages SET content = :content WHERE id = :messageId AND content = :expectedContent")
    suspend fun updateMessageContentIfUnchanged(
        messageId: String,
        expectedContent: String,
        content: String
    ): Int

    /**
     * Monotonic delivery update: only advances SENDING→SENT→DELIVERED→READ.
     * FAILED is a local side-channel and must not be applied through this path for remote events.
     */
    @Query(
        """
        UPDATE messages SET status = :status
        WHERE id = :messageId
          AND (
            (:status = 'SENDING' AND status IN ('FAILED'))
            OR (:status = 'SENT' AND status IN ('SENDING', 'FAILED'))
            OR (:status = 'DELIVERED' AND status IN ('SENDING', 'SENT'))
            OR (:status = 'READ' AND status IN ('SENDING', 'SENT', 'DELIVERED'))
            OR (:status = 'FAILED' AND status = 'SENDING')
          )
        """
    )
    suspend fun updateMessageStatusIfAdvanced(messageId: String, status: String): Int

    @Query("UPDATE messages SET starred = :starred WHERE id = :messageId")
    suspend fun setStarred(messageId: String, starred: Boolean)

    @Query(
        """
        UPDATE messages SET status = 'READ'
        WHERE chatId = :chatId AND senderId != :ownerUserId
          AND type != 'SK_DIST'
          AND (timestamp < :throughTimestamp OR (timestamp = :throughTimestamp AND id <= :throughMessageId))
        """,
    )
    suspend fun markIncomingReadThrough(
        chatId: String,
        ownerUserId: String,
        throughTimestamp: Long,
        throughMessageId: String,
    ): Int

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: String)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteMessagesByChatId(chatId: String)

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteMessagesByIds(ids: List<String>)

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()
}
