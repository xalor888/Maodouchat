package com.maodouchat.session

import com.maodouchat.core.model.WebSocketEvent

data class RealtimeTransportEvent(
    val connectionGeneration: Long,
    val event: WebSocketEvent,
)
