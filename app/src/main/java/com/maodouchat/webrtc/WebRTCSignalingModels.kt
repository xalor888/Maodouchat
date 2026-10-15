package com.maodouchat.webrtc

import kotlinx.serialization.Serializable

enum class FailureOperation { SEND, FETCH, HANG_UP }

class SignalingException(
    val operation: FailureOperation,
    val statusCode: Int? = null,
    val code: String? = null,
    val retryAfterSeconds: Long? = null
) :
    Exception("WebRTC signaling ${operation.name.lowercase()} failed")

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
