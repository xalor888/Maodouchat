package com.maodouchat.network

import kotlinx.serialization.Serializable

@Serializable
data class SignalingSendRequest(
    val toUserId: String,
    val type: String,
    val payload: String,
    val callId: String = "",
    val groupId: String = "",
    val groupMemberIds: List<String> = emptyList(),
    val groupInvite: Boolean = false
)

@Serializable
data class SignalMessageDto(
    val id: String,
    val fromUserId: String,
    val type: String,
    val payload: String,
    val timestamp: Long,
    val callId: String = "",
    val groupId: String = "",
    val groupMemberIds: List<String> = emptyList(),
    val groupInvite: Boolean = false
)

@Serializable
data class IceServerDto(
    val urls: List<String>,
    val username: String = "",
    val credential: String = ""
)

@Serializable
data class IceConfigDto(
    val iceServers: List<IceServerDto> = emptyList(),
    val expiresAt: Long = 0,
    val turnEnabled: Boolean = false
)
