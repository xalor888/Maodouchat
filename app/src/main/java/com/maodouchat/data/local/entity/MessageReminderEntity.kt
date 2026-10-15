package com.maodouchat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.maodouchat.util.MessageReminder

@Entity(
    tableName = "message_reminders",
    indices = [
        Index(value = ["ownerUserId"]),
        Index(value = ["chatId"]),
        Index(value = ["remindAtMillis"]),
        Index(value = ["ownerUserId", "chatId"]),
    ],
)
data class MessageReminderEntity(
    @PrimaryKey
    val id: String,
    val ownerUserId: String,
    val chatId: String,
    val messageId: String,
    val messagePreview: String,
    val remindAtMillis: Long,
    val createdAtMillis: Long,
)

fun MessageReminderEntity.toModel(): MessageReminder = MessageReminder(
    id = id,
    chatId = chatId,
    messageId = messageId,
    messagePreview = messagePreview,
    remindAtMillis = remindAtMillis,
    createdAtMillis = createdAtMillis,
    ownerUserId = ownerUserId,
)

fun MessageReminder.toEntity(): MessageReminderEntity = MessageReminderEntity(
    id = id,
    ownerUserId = ownerUserId,
    chatId = chatId,
    messageId = messageId,
    messagePreview = messagePreview,
    remindAtMillis = remindAtMillis,
    createdAtMillis = createdAtMillis,
)

