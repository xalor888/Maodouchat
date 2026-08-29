package com.maodouchat.push

import android.content.Context
import com.maodouchat.network.ApiConfig
import com.maodouchat.network.TokenManager
import com.maodouchat.network.WebSocketClient

interface PushTransport {
    fun ensureForegroundConnection(): PushTransportState
    fun onNetworkAvailable(): PushTransportState = ensureForegroundConnection()
    fun stop()
}

sealed interface PushTransportState {
    data class Connected(val ownerUserId: String) : PushTransportState
    data class Connecting(val ownerUserId: String) : PushTransportState
    data object NoSession : PushTransportState
    data object Stopped : PushTransportState
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

internal fun androidPushTransport(context: Context): PushTransport {
    val tokenManager = TokenManager.getInstance(context.applicationContext)
    return PushTransportController(
        session = {
            val owner = tokenManager.getUserId().orEmpty()
            val token = tokenManager.getToken().orEmpty()
            if (owner.isBlank() || token.isBlank()) null else owner to token
        },
        isConnected = WebSocketClient::isConnected,
        socket = PushSocketPort { url, token -> WebSocketClient.connect(url, token) },
        url = ApiConfig.WS_URL,
    )
}
