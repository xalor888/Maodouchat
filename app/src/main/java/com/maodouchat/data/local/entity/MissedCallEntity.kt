package com.maodouchat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 未接来电记录：用户不在前台时收到了 offer 但未在 30s 内应答，标记为未接
 */
@Entity(tableName = "missed_calls", indices = [Index("receivedAt"), Index("isRead")])
data class MissedCallEntity(
    @PrimaryKey val id: String,
    val callerId: String,
    val callerName: String,
    val callType: String, // AUDIO / VIDEO
    val receivedAt: Long,
    val isRead: Boolean = false
) {
    fun toDomain(): com.maodouchat.data.model.MissedCall = com.maodouchat.data.model.MissedCall(
        id = id,
        callerId = callerId,
        callerName = callerName,
        callType = callType,
        receivedAt = receivedAt,
        isRead = isRead
    )
}

fun com.maodouchat.data.model.MissedCall.toEntity(): MissedCallEntity = MissedCallEntity(
    id = id,
    callerId = callerId,
    callerName = callerName,
    callType = callType,
    receivedAt = receivedAt,
    isRead = isRead
)