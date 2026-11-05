package com.maodouchat.webrtc

import kotlinx.serialization.Serializable

@Serializable
internal data class SendSignalRequest(
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

@Serializable
data class SignalMessage(
    val id: String,
    val fromUserId: String,
    val type: String,
    val payload: String,
    val timestamp: Long,
    val callId: String = "",
    val groupId: String = "",
    val groupMemberIds: List<String> = emptyList(),
    val groupInvite: Boolean = false,
    val epoch: Long = 0,
    val sequence: Long = 0,
    val idempotencyKey: String = "",
)
