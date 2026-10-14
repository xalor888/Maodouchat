package com.maodouchat.session

import com.maodouchat.network.WebSocketEvent

data class RealtimeTransportEvent(
    val connectionGeneration: Long,
    val event: WebSocketEvent,
)
