package com.maodouchat.network

import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSocketNetworkErrorPolicyTest {

    @Test
    fun refusedConnectionRemainsRecoverable() {
        assertFalse(isNonRecoverableWebSocketNetworkError(ConnectException("refused")))
        assertFalse(isNonRecoverableWebSocketNetworkError(UnknownHostException("temporary")))
    }

    @Test
    fun sslFailureStopsAutomaticReconnectEvenWhenWrapped() {
        assertTrue(isNonRecoverableWebSocketNetworkError(SSLHandshakeException("certificate")))
        assertTrue(
            isNonRecoverableWebSocketNetworkError(
                IOException("websocket failed", SSLHandshakeException("certificate"))
            )
        )
    }
}
