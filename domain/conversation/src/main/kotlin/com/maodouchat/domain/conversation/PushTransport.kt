package com.maodouchat.domain.conversation

interface PushTransport {
    fun ensureForegroundConnection(): PushTransportState
    fun onNetworkAvailable(): PushTransportState = ensureForegroundConnection()
    fun stop()
}

fun interface PushSocketPort {
    fun connect(url: String, token: String)
}

class PushTransportController(
    private val session: () -> Pair<String, String>?,
    private val isConnected: () -> Boolean,
    private val socket: PushSocketPort,
    private val url: String,
) : PushTransport {
    override fun ensureForegroundConnection(): PushTransportState {
        val (ownerUserId, token) = session() ?: return PushTransportState.NoSession
        if (ownerUserId.isBlank() || token.isBlank()) return PushTransportState.NoSession
        if (isConnected()) return PushTransportState.Connected(ownerUserId)
        socket.connect(url, token)
        return PushTransportState.Connecting(ownerUserId)
    }

    override fun stop() = Unit
}
