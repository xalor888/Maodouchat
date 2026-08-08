package com.maodouchat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.maodouchat.data.local.entity.ChatLockEntity

@Dao
interface ChatLockDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(lock: ChatLockEntity)

    @Query("SELECT * FROM chat_locks WHERE chatId = :chatId")
    suspend fun get(chatId: String): ChatLockEntity?

    @Query("DELETE FROM chat_locks WHERE chatId = :chatId")
    suspend fun remove(chatId: String)

    @Query("SELECT chatId FROM chat_locks")
    suspend fun listLockedChatIds(): List<String>

    @Query("DELETE FROM chat_locks")
    suspend fun deleteAll()
}