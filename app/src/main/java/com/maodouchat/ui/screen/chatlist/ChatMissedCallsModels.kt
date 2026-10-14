package com.maodouchat.ui.screen.chatlist

internal data class CallLogRow(
    val id: String,
    val peerId: String,
    val peerName: String,
    val video: Boolean,
    val direction: com.maodouchat.call.CallLogStore.Direction,
    val state: com.maodouchat.call.CallLogStore.State,
    val at: Long,
    val durationMs: Long
)
