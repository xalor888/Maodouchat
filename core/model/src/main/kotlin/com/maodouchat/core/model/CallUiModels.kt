package com.maodouchat.core.model

data class GroupCallParticipantUi(
    val userId: String,
    val name: String = userId,
    val avatar: String? = null,
    val connectionState: GroupPeerConnectionState = GroupPeerConnectionState.CONNECTING,
    val videoAvailable: Boolean = false
)
