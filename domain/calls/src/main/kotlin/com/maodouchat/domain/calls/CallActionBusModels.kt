package com.maodouchat.domain.calls

data class HangUpRequest(
    val callId: String,
    val notifyPeer: Boolean = true,
    val sessionGeneration: Long,
)
