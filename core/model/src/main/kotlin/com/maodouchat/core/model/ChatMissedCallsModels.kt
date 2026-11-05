package com.maodouchat.core.model

data class CallLogRow(
    val id: String,
    val peerId: String,
    val peerName: String,
    val video: Boolean,
    val direction: Direction,
    val state: State,
    val at: Long,
    val durationMs: Long
)
