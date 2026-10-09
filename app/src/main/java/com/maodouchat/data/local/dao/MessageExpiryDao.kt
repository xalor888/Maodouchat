package com.maodouchat.data.local.dao

import androidx.room.Query

interface MessageExpiryDao {
    @Query("SELECT id FROM messages WHERE expiresAt IS NOT NULL AND expiresAt > 0 AND expiresAt <= :now")
    suspend fun getExpiredMessageIds(now: Long): List<String>

    @Query("SELECT id FROM messages WHERE chatId = :chatId AND expiresAt IS NOT NULL AND expiresAt > 0 AND expiresAt <= :now")
    suspend fun getExpiredMessageIdsForChat(chatId: String, now: Long): List<String>

    @Query("""
        UPDATE messages
        SET expiresAt = :expiresAt
        WHERE chatId = :chatId
          AND type NOT IN ('SK_DIST', 'REVOKED')
          AND (expiresAt IS NULL OR expiresAt <= 0 OR expiresAt > :expiresAt)
    """)
    suspend fun armMessagesOnRead(chatId: String, expiresAt: Long): Int

    @Query("SELECT MIN(expiresAt) FROM messages WHERE expiresAt IS NOT NULL AND expiresAt > :now")
    suspend fun getEarliestExpiryAfter(now: Long): Long?

    @Query("UPDATE messages SET expiresAt = :expiresAt WHERE id = :messageId AND (expiresAt IS NULL OR expiresAt <= 0 OR expiresAt > :expiresAt)")
    suspend fun updateMessageExpiresAt(messageId: String, expiresAt: Long): Int
}
