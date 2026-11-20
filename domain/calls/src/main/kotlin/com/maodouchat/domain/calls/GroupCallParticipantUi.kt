package com.maodouchat.domain.calls

import com.maodouchat.core.model.GroupPeerConnectionState

data class GroupCallParticipantUi(
    val userId: String,
    val name: String = userId,
    val avatar: String? = null,
    val connectionState: GroupPeerConnectionState = GroupPeerConnectionState.CONNECTING,
    val videoAvailable: Boolean = false
)
