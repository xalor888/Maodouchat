package com.maodouchat.call

enum class Direction { OUTGOING, INCOMING }

enum class State { MISSED, ANSWERED }

data class CallLogEntry(
    val id: String,
    val peerId: String,
    val peerName: String,
    val isVideo: Boolean,
    val direction: Direction,
    val state: State,
    val startedAt: Long,
    val durationMs: Long = 0L,
    val isGroup: Boolean = false
)
