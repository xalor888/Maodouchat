package com.maodouchat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.maodouchat.data.local.entity.SecretChatEntity

/**
 * 密聊本机 TTL 心跳。是不是密聊看 [ChatDao.isSecretChat]（chatType=SECRET），不看这张表。
 */
@Dao
interface SecretChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SecretChatEntity)

    @Query("SELECT * FROM secret_chats WHERE chatId = :chatId LIMIT 1")
    suspend fun get(chatId: String): SecretChatEntity?

    @Query("UPDATE secret_chats SET lastActivityAt = :now WHERE chatId = :chatId")
    suspend fun touchActivity(chatId: String, now: Long = System.currentTimeMillis())

    @Query("SELECT chatId, lastActivityAt FROM secret_chats")
    suspend fun listActivity(): List<SecretChatActivity>

    data class SecretChatActivity(val chatId: String, val lastActivityAt: Long)

    @Query("DELETE FROM secret_chats WHERE chatId = :chatId")
    suspend fun remove(chatId: String)

    @Query("DELETE FROM secret_chats")
    suspend fun deleteAll()
}
