package com.maodouchat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scheduled_messages",
    indices = [
        Index(value = ["ownerUserId"]),
        Index(value = ["chatId"]),
        Index(value = ["sendAtMillis"]),
        Index(value = ["idempotencyKey"]),
        Index(value = ["ownerUserId", "chatId"]),
    ],
)
data class ScheduledMessageEntity(
    @PrimaryKey
    val id: String,
    val ownerUserId: String,
    val chatId: String,
    val peerUserId: String,
    val text: String,
    val sendAtMillis: Long,
    val createdAtMillis: Long,
    val isGroup: Boolean,
    val status: String,
    val attempt: Int,
    val repeatIntervalMs: Long,
    val repeatCount: Int,
    val occurrencesSent: Int,
    val weekdaysOnly: Boolean,
    val timeZoneId: String,
    val idempotencyKey: String,
)

fun ScheduledMessageEntity.toModel(): com.maodouchat.util.ScheduledMessage = com.maodouchat.util.ScheduledMessage(
    id = id,
    chatId = chatId,
    peerUserId = peerUserId,
    text = text,
    sendAtMillis = sendAtMillis,
    createdAtMillis = createdAtMillis,
    isGroup = isGroup,
    ownerUserId = ownerUserId,
    repeatIntervalMs = repeatIntervalMs,
    repeatCount = repeatCount,
    occurrencesSent = occurrencesSent,
    weekdaysOnly = weekdaysOnly,
    status = status,
    attempt = attempt,
    timeZoneId = timeZoneId,
    idempotencyKey = idempotencyKey,
)

fun com.maodouchat.util.ScheduledMessage.toEntity(): ScheduledMessageEntity = ScheduledMessageEntity(
    id = id,
    ownerUserId = ownerUserId,
    chatId = chatId,
    peerUserId = peerUserId,
    text = text,
    sendAtMillis = sendAtMillis,
    createdAtMillis = createdAtMillis,
    isGroup = isGroup,
    status = status,
    attempt = attempt,
    repeatIntervalMs = repeatIntervalMs,
    repeatCount = repeatCount,
    occurrencesSent = occurrencesSent,
    weekdaysOnly = weekdaysOnly,
    timeZoneId = timeZoneId,
    idempotencyKey = idempotencyKey,
)

