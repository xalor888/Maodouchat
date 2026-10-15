package com.maodouchat.call

import com.maodouchat.MaodouchatApp

data class HangUpRequest(
    val callId: String,
    val notifyPeer: Boolean = true,
    val sessionGeneration: Long = MaodouchatApp.currentSessionGeneration(),
)
