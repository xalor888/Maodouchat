package com.maodouchat.network

import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
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

    @Test
    fun plainIoFailureWithoutSslCauseRemainsRecoverable() {
        assertFalse(isNonRecoverableWebSocketNetworkError(IOException("read timed out")))
    }

    @Test
    fun sslExceptionNestedTwoLevelsDeepStillStopsReconnect() {
        assertTrue(
            isNonRecoverableWebSocketNetworkError(
                RuntimeException(IOException("wrap", SSLHandshakeException("certificate")))
            )
        )
    }

    @Test
    fun peerUnverifiedErrorStopsAutomaticReconnect() {
        // SSLPeerUnverifiedException 是 SSLException 的子类
        assertTrue(isNonRecoverableWebSocketNetworkError(SSLPeerUnverifiedException("peer not authenticated")))
    }

    @Test
    fun nonSslWrapperAroundConnectFailureRemainsRecoverable() {
        assertFalse(
            isNonRecoverableWebSocketNetworkError(IOException("connect", ConnectException("refused")))
        )
    }

    @Test
    fun plainRuntimeErrorWithoutCauseRemainsRecoverable() {
        assertFalse(isNonRecoverableWebSocketNetworkError(IllegalStateException("bug")))
    }
}
