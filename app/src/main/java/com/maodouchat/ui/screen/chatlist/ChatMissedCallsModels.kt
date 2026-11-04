package com.maodouchat.ui.screen.chatlist

import com.maodouchat.core.model.Direction
import com.maodouchat.core.model.State

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
