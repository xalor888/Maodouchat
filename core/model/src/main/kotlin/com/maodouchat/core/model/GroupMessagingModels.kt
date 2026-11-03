package com.maodouchat.core.model

data class GroupMessagingSession(
    val ownerUserId: String,
    val authToken: String,
    val deviceId: Int,
)
