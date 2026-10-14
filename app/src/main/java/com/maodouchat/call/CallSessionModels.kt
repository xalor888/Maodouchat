package com.maodouchat.call

enum class Phase {
    IDLE,
    OUTGOING_RINGING,
    INCOMING_RINGING,
    CONNECTING,
    CONNECTED,
    ENDING,
    ENDED,
}

data class Snapshot(
    val epoch: Long = 0L,
    val phase: Phase = Phase.IDLE,
    val peerId: String = "",
    val incoming: Boolean = false,
)
