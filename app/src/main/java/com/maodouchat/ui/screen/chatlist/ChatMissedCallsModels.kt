package com.maodouchat.ui.screen.chatlist

import com.maodouchat.call.Direction
import com.maodouchat.call.State

internal data class CallLogRow(
    val id: String,
    val peerId: String,
    val peerName: String,
    val video: Boolean,
    val direction: Direction,
    val state: State,
    val at: Long,
    val durationMs: Long
)
