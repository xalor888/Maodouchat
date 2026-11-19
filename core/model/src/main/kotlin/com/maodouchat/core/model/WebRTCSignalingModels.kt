package com.maodouchat.core.model

import kotlinx.serialization.Serializable

@Serializable
data class SendSignalRequest(
    val toUserId: String,
    val type: String,
    val payload: String,
    val callId: String = "",
    val groupId: String = "",
    val groupMemberIds: List<String> = emptyList(),
    val groupInvite: Boolean = false,
    val epoch: Long = 0,
    val sequence: Long = 0,
    val idempotencyKey: String = "",
)
