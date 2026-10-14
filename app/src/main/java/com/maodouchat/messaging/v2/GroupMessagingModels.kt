package com.maodouchat.messaging.v2

data class GroupMessagingSession(
    val ownerUserId: String,
    val authToken: String,
    val deviceId: Int,
)
