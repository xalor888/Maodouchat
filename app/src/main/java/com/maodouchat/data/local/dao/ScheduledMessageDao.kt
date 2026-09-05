package com.maodouchat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.maodouchat.data.local.entity.ScheduledMessageEntity

@Dao
interface ScheduledMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ScheduledMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertBlocking(entity: ScheduledMessageEntity)

    @Query("DELETE FROM scheduled_messages WHERE id = :id AND ownerUserId = :ownerUserId")
    suspend fun deleteById(id: String, ownerUserId: String): Int

    @Query("DELETE FROM scheduled_messages WHERE id = :id AND ownerUserId = :ownerUserId")
    fun deleteByIdBlocking(id: String, ownerUserId: String): Int

    @Query("DELETE FROM scheduled_messages WHERE id = :id")
    suspend fun deleteByIdWithoutOwner(id: String): Int

    @Query("DELETE FROM scheduled_messages WHERE id = :id")
    fun deleteByIdWithoutOwnerBlocking(id: String): Int

    @Query("DELETE FROM scheduled_messages WHERE ownerUserId = :ownerUserId AND chatId = :chatId")
    suspend fun deleteForChat(ownerUserId: String, chatId: String): Int

    @Query("DELETE FROM scheduled_messages WHERE ownerUserId = :ownerUserId AND chatId = :chatId")
    fun deleteForChatBlocking(ownerUserId: String, chatId: String): Int

    @Query("DELETE FROM scheduled_messages WHERE ownerUserId = :ownerUserId")
    suspend fun deleteForUser(ownerUserId: String): Int

    @Query("DELETE FROM scheduled_messages WHERE ownerUserId = :ownerUserId")
    fun deleteForUserBlocking(ownerUserId: String): Int

    @Query("SELECT * FROM scheduled_messages WHERE id = :id AND ownerUserId = :ownerUserId LIMIT 1")
    suspend fun getById(id: String, ownerUserId: String): ScheduledMessageEntity?

    @Query("SELECT * FROM scheduled_messages WHERE id = :id AND ownerUserId = :ownerUserId LIMIT 1")
    fun getByIdBlocking(id: String, ownerUserId: String): ScheduledMessageEntity?

    @Query("SELECT * FROM scheduled_messages WHERE id = :id LIMIT 1")
    suspend fun getByIdWithoutOwner(id: String): ScheduledMessageEntity?

    @Query("SELECT * FROM scheduled_messages WHERE id = :id LIMIT 1")
    fun getByIdWithoutOwnerBlocking(id: String): ScheduledMessageEntity?

    @Query("SELECT * FROM scheduled_messages WHERE ownerUserId = :ownerUserId ORDER BY sendAtMillis ASC")
    suspend fun listForUser(ownerUserId: String): List<ScheduledMessageEntity>

    @Query("SELECT * FROM scheduled_messages WHERE ownerUserId = :ownerUserId ORDER BY sendAtMillis ASC")
    fun listForUserBlocking(ownerUserId: String): List<ScheduledMessageEntity>

    @Query("SELECT * FROM scheduled_messages WHERE ownerUserId = :ownerUserId AND chatId = :chatId ORDER BY sendAtMillis ASC")
    suspend fun listForChat(ownerUserId: String, chatId: String): List<ScheduledMessageEntity>

    @Query("SELECT * FROM scheduled_messages WHERE ownerUserId = :ownerUserId AND chatId = :chatId ORDER BY sendAtMillis ASC")
    fun listForChatBlocking(ownerUserId: String, chatId: String): List<ScheduledMessageEntity>

    @Query("SELECT * FROM scheduled_messages WHERE ownerUserId = :ownerUserId AND sendAtMillis <= :nowMillis AND status = 'PENDING' ORDER BY sendAtMillis ASC")
    suspend fun dueMessages(ownerUserId: String, nowMillis: Long): List<ScheduledMessageEntity>

    @Query("UPDATE scheduled_messages SET text = :text, sendAtMillis = :sendAtMillis, timeZoneId = :timeZoneId WHERE id = :id AND ownerUserId = :ownerUserId")
    suspend fun updateTextAndTime(id: String, ownerUserId: String, text: String, sendAtMillis: Long, timeZoneId: String): Int

    @Query("UPDATE scheduled_messages SET text = :text, sendAtMillis = :sendAtMillis, timeZoneId = :timeZoneId WHERE id = :id AND ownerUserId = :ownerUserId")
    fun updateTextAndTimeBlocking(id: String, ownerUserId: String, text: String, sendAtMillis: Long, timeZoneId: String): Int

    @Query("UPDATE scheduled_messages SET status = :status, attempt = :attempt WHERE id = :id AND ownerUserId = :ownerUserId")
    suspend fun updateStatus(id: String, ownerUserId: String, status: String, attempt: Int): Int

    @Query("UPDATE scheduled_messages SET status = :status, attempt = :attempt WHERE id = :id AND ownerUserId = :ownerUserId")
    fun updateStatusBlocking(id: String, ownerUserId: String, status: String, attempt: Int): Int
}
