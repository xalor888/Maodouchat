package com.maodouchat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.maodouchat.data.local.entity.MissedCallEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MissedCallDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(call: MissedCallEntity)

    @Query("SELECT * FROM missed_calls WHERE receivedAt >= :since ORDER BY receivedAt DESC")
    fun observeRecent(since: Long = 0L): Flow<List<MissedCallEntity>>

    @Query("SELECT COUNT(*) FROM missed_calls WHERE isRead = 0")
    fun observeUnreadCount(): Flow<Int>

    @Query("UPDATE missed_calls SET isRead = 1")
    suspend fun markAllRead()

    @Query("DELETE FROM missed_calls WHERE receivedAt < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)

    @Query("DELETE FROM missed_calls WHERE id = :callId")
    suspend fun deleteById(callId: String)

    @Query("DELETE FROM missed_calls")
    suspend fun deleteAll()
}
