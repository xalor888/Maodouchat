package com.maodouchat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.maodouchat.data.local.entity.SenderKeyRetryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SenderKeyRetryDao {

    @Query("SELECT * FROM sender_key_retry_queue WHERE ownerUserId = :ownerUserId AND chatId = :chatId")
    suspend fun get(ownerUserId: String, chatId: String): SenderKeyRetryEntity?

    @Query("SELECT * FROM sender_key_retry_queue WHERE ownerUserId = :ownerUserId AND nextAttemptAt <= :now ORDER BY nextAttemptAt ASC LIMIT :limit")
    suspend fun getDue(ownerUserId: String, now: Long, limit: Int = 5): List<SenderKeyRetryEntity>

    @Query("SELECT * FROM sender_key_retry_queue WHERE ownerUserId = :ownerUserId AND nextAttemptAt < 9223372036854775807 ORDER BY nextAttemptAt ASC")
    fun observeAll(ownerUserId: String): Flow<List<SenderKeyRetryEntity>>

    @Query("SELECT MIN(nextAttemptAt) FROM sender_key_retry_queue WHERE ownerUserId = :ownerUserId AND nextAttemptAt < 9223372036854775807")
    suspend fun getNextAttemptAt(ownerUserId: String): Long?

    @Query("SELECT COUNT(*) FROM sender_key_retry_queue WHERE ownerUserId = :ownerUserId AND chatId = :chatId AND nextAttemptAt < 9223372036854775807")
    fun observePendingCountForChat(ownerUserId: String, chatId: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: SenderKeyRetryEntity)

    // 8.48 修复：收养 v24→v25 迁移遗留的孤儿行（ownerUserId=''，迁移注释声称
    // 「重试时会重新绑定当前用户」但无任何绑定代码 → 永久失联）。处理前将孤儿行
    // 归属到当前账号。
    @Query("UPDATE sender_key_retry_queue SET ownerUserId = :ownerUserId WHERE ownerUserId = ''")
    suspend fun adoptOrphans(ownerUserId: String): Int

    @Query("DELETE FROM sender_key_retry_queue WHERE ownerUserId = :ownerUserId AND chatId = :chatId")
    suspend fun delete(ownerUserId: String, chatId: String)

    @Query("DELETE FROM sender_key_retry_queue WHERE ownerUserId = :ownerUserId")
    suspend fun deleteForOwner(ownerUserId: String)

    @Query("DELETE FROM sender_key_retry_queue")
    suspend fun deleteAll()
}
