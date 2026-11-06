package com.maodouchat.core.realtime

import com.maodouchat.core.model.WebSocketEvent
import kotlinx.coroutines.flow.SharedFlow

data class RealtimeTransportEvent(
    val connectionGeneration: Long,
    val event: WebSocketEvent,
)

interface RealtimeTransport {
    val events: SharedFlow<RealtimeTransportEvent>

    fun open(serverUrl: String, accessToken: String, reconnect: Boolean): Long?
    fun disconnect()
    fun isConnected(): Boolean
    fun sendRaw(text: String): Boolean
    fun sendTyping(chatId: String, isTyping: Boolean): Boolean
    fun sendPresence(foreground: Boolean): Boolean
}
