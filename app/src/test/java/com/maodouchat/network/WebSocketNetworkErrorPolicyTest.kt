package com.maodouchat.network

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLKeyException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CancellationException
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

    @Test
    fun sslExceptionBaseClassStopsReconnect() {
        assertTrue(isNonRecoverableWebSocketNetworkError(SSLException("generic ssl failure")))
    }

    @Test
    fun sslCauseNestedThreeLevelsDeepStillStopsReconnect() {
        assertTrue(
            isNonRecoverableWebSocketNetworkError(
                IOException("l1", IOException("l2", SSLHandshakeException("certificate")))
            )
        )
    }

    @Test
    fun cancellationExceptionRemainsRecoverable() {
        assertFalse(isNonRecoverableWebSocketNetworkError(CancellationException("job cancelled")))
    }

    @Test
    fun recoverableWrapperAroundSslCauseStillStopsReconnect() {
        assertTrue(
            isNonRecoverableWebSocketNetworkError(
                UnknownHostException("dns").apply { initCause(SSLHandshakeException("certificate")) }
            )
        )
    }

    @Test
    fun interruptedIoExceptionRemainsRecoverable() {
        assertFalse(isNonRecoverableWebSocketNetworkError(InterruptedIOException("interrupted")))
    }

    @Test
    fun sslCauseCarriedByCancellationStillStopsReconnect() {
        val cancelled = CancellationException("job cancelled")
        cancelled.initCause(SSLHandshakeException("certificate"))
        assertTrue(isNonRecoverableWebSocketNetworkError(cancelled))
    }

    @Test
    fun sslCauseFourLevelsDeepStillStopsReconnect() {
        assertTrue(
            isNonRecoverableWebSocketNetworkError(
                IOException(
                    "l1",
                    IOException("l2", IOException("l3", SSLHandshakeException("certificate")))
                )
            )
        )
    }

    @Test
    fun deepPlainChainWithoutSslRemainsRecoverable() {
        assertFalse(
            isNonRecoverableWebSocketNetworkError(
                IOException("l1", IOException("l2", IOException("l3", ConnectException("refused"))))
            )
        )
    }

    @Test
    fun sslKeyExceptionSubclassStopsReconnect() {
        assertTrue(isNonRecoverableWebSocketNetworkError(SSLKeyException("bad key")))
    }

    @Test
    fun sslMentionedOnlyInMessageRemainsRecoverable() {
        assertFalse(isNonRecoverableWebSocketNetworkError(IOException("SSLException during handshake")))
    }
}
