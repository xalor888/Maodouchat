package com.maodouchat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.maodouchat.data.local.entity.SecretChatEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SecretChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SecretChatEntity)

    @Query("SELECT * FROM secret_chats WHERE chatId = :chatId LIMIT 1")
    suspend fun get(chatId: String): SecretChatEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM secret_chats WHERE chatId = :chatId)")
    suspend fun isSecret(chatId: String): Boolean

    @Query("SELECT chatId FROM secret_chats")
    suspend fun listSecretChatIds(): List<String>

    /** 密聊活动时间戳（供无活动 TTL 清扫）。 */
    @Query("UPDATE secret_chats SET lastActivityAt = :now WHERE chatId = :chatId")
    suspend fun touchActivity(chatId: String, now: Long = System.currentTimeMillis())

    /** 全量活动时间表（供 SecretSessionTtl.sweepExpired）。 */
    @Query("SELECT chatId, lastActivityAt FROM secret_chats")
    suspend fun listActivity(): List<SecretChatActivity>

    data class SecretChatActivity(val chatId: String, val lastActivityAt: Long)

    @Query("SELECT chatId FROM secret_chats")
    fun observeSecretChatIds(): Flow<List<String>>

    @Query("DELETE FROM secret_chats WHERE chatId = :chatId")
    suspend fun remove(chatId: String)

    @Query("DELETE FROM secret_chats")
    suspend fun deleteAll()
}
