package com.maodouchat.data.model

data class MissedCall(
    val id: String,
    val callerId: String,
    val callerName: String,
    val callType: String,
    val receivedAt: Long,
    val isRead: Boolean = false
)