package com.maodouchat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.maodouchat.data.local.entity.MessageReminderEntity

@Dao
interface MessageReminderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MessageReminderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertBlocking(entity: MessageReminderEntity)

    @Query("DELETE FROM message_reminders WHERE id = :id AND ownerUserId = :ownerUserId")
    suspend fun deleteById(id: String, ownerUserId: String): Int

    @Query("DELETE FROM message_reminders WHERE id = :id AND ownerUserId = :ownerUserId")
    fun deleteByIdBlocking(id: String, ownerUserId: String): Int

    @Query("DELETE FROM message_reminders WHERE ownerUserId = :ownerUserId AND chatId = :chatId")
    suspend fun deleteForChat(ownerUserId: String, chatId: String): Int

    @Query("DELETE FROM message_reminders WHERE ownerUserId = :ownerUserId AND chatId = :chatId")
    fun deleteForChatBlocking(ownerUserId: String, chatId: String): Int

    @Query("DELETE FROM message_reminders WHERE ownerUserId = :ownerUserId")
    suspend fun deleteForUser(ownerUserId: String): Int

    @Query("DELETE FROM message_reminders WHERE ownerUserId = :ownerUserId")
    fun deleteForUserBlocking(ownerUserId: String): Int

    @Query("SELECT * FROM message_reminders WHERE id = :id AND ownerUserId = :ownerUserId LIMIT 1")
    suspend fun getById(id: String, ownerUserId: String): MessageReminderEntity?

    @Query("SELECT * FROM message_reminders WHERE id = :id AND ownerUserId = :ownerUserId LIMIT 1")
    fun getByIdBlocking(id: String, ownerUserId: String): MessageReminderEntity?

    @Query("SELECT * FROM message_reminders WHERE ownerUserId = :ownerUserId ORDER BY remindAtMillis ASC")
    suspend fun listForUser(ownerUserId: String): List<MessageReminderEntity>

    @Query("SELECT * FROM message_reminders WHERE ownerUserId = :ownerUserId ORDER BY remindAtMillis ASC")
    fun listForUserBlocking(ownerUserId: String): List<MessageReminderEntity>

    @Query("SELECT * FROM message_reminders WHERE ownerUserId = :ownerUserId AND chatId = :chatId ORDER BY remindAtMillis ASC")
    suspend fun listForChat(ownerUserId: String, chatId: String): List<MessageReminderEntity>

    @Query("SELECT * FROM message_reminders WHERE ownerUserId = :ownerUserId AND chatId = :chatId ORDER BY remindAtMillis ASC")
    fun listForChatBlocking(ownerUserId: String, chatId: String): List<MessageReminderEntity>
}
