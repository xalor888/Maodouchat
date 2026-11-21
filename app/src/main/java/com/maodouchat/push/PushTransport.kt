package com.maodouchat.push

import android.content.Context
import com.maodouchat.domain.conversation.PushSocketPort
import com.maodouchat.domain.conversation.PushTransport
import com.maodouchat.domain.conversation.PushTransportController
import com.maodouchat.network.ApiConfig
import com.maodouchat.network.TokenManager
import com.maodouchat.network.WebSocketClient

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
