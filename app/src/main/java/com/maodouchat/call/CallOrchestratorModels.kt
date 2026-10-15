package com.maodouchat.call

import com.maodouchat.webrtc.CallType

data class GroupCallRequest(
    val chatId: String,
    val memberIds: List<String>,
    val callType: CallType,
    val sessionGeneration: Long = CallOrchestrator.currentSessionGeneration(),
)

data class DirectChatRequest(
    val userId: String,
    val userName: String,
    val sessionGeneration: Long = CallOrchestrator.currentSessionGeneration(),
)
