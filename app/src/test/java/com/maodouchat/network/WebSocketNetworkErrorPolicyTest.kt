package com.maodouchat.network

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLKeyException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException
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

    @Test
    fun sslProtocolExceptionSubclassStopsReconnect() {
        assertTrue(isNonRecoverableWebSocketNetworkError(SSLProtocolException("protocol error")))
    }

    @Test
    fun socketTimeoutRemainsRecoverable() {
        assertFalse(isNonRecoverableWebSocketNetworkError(SocketTimeoutException("read timed out")))
    }

    @Test
    fun sslCauseUnderRuntimeExceptionStopsReconnect() {
        assertTrue(
            isNonRecoverableWebSocketNetworkError(
                RuntimeException("wrap", SSLException("generic ssl failure"))
            )
        )
    }

    @Test
    fun unknownHostWrappingConnectFailureRemainsRecoverable() {
        assertFalse(
            isNonRecoverableWebSocketNetworkError(
                UnknownHostException("dns").apply { initCause(ConnectException("refused")) }
            )
        )
    }

    @Test
    fun interruptedIoWithSslCauseStopsReconnect() {
        assertTrue(
            isNonRecoverableWebSocketNetworkError(
                InterruptedIOException("interrupted").apply {
                    initCause(SSLHandshakeException("certificate"))
                }
            )
        )
    }

    @Test
    fun sslCauseFiveLevelsDeepStillStopsReconnect() {
        assertTrue(
            isNonRecoverableWebSocketNetworkError(
                IOException(
                    "l1",
                    IOException(
                        "l2",
                        IOException("l3", IOException("l4", SSLHandshakeException("certificate")))
                    )
                )
            )
        )
    }

    @Test
    fun sslPeerUnverifiedNestedUnderTwoIoLayersStopsReconnect() {
        assertTrue(
            isNonRecoverableWebSocketNetworkError(
                IOException("l1", IOException("l2", SSLPeerUnverifiedException("peer not authenticated")))
            )
        )
    }

    @Test
    fun noArgIoFailureRemainsRecoverable() {
        assertFalse(isNonRecoverableWebSocketNetworkError(IOException()))
    }

    @Test
    fun sslKeyExceptionNestedUnderRuntimeStopsReconnect() {
        assertTrue(
            isNonRecoverableWebSocketNetworkError(
                RuntimeException("wrap", SSLKeyException("bad key"))
            )
        )
    }

    @Test
    fun ioChainEndingInSocketTimeoutRemainsRecoverable() {
        assertFalse(
            isNonRecoverableWebSocketNetworkError(
                IOException("l1", SocketTimeoutException("read timed out"))
            )
        )
    }

    @Test
    fun sslProtocolExceptionNestedUnderRuntimeStopsReconnect() {
        assertTrue(
            isNonRecoverableWebSocketNetworkError(
                RuntimeException("wrap", SSLProtocolException("protocol error"))
            )
        )
    }

    @Test
    fun socketTimeoutWithSslCauseStopsReconnect() {
        assertTrue(
            isNonRecoverableWebSocketNetworkError(
                SocketTimeoutException("read timed out").apply {
                    initCause(SSLHandshakeException("certificate"))
                }
            )
        )
    }

    @Test
    fun unknownHostMentioningSslInMessageRemainsRecoverable() {
        assertFalse(isNonRecoverableWebSocketNetworkError(UnknownHostException("SSL handshake failed")))
    }

    @Test
    fun sslExceptionWithMessageStopsReconnect() {
        assertTrue(isNonRecoverableWebSocketNetworkError(SSLException("generic ssl failure")))
    }

    @Test
    fun ioChainEndingInUnknownHostRemainsRecoverable() {
        assertFalse(
            isNonRecoverableWebSocketNetworkError(
                IOException("l1", IOException("l2", UnknownHostException("dns")))
            )
        )
    }

    @Test
    fun sslCauseUnderConnectFailureStopsReconnect() {
        assertTrue(
            isNonRecoverableWebSocketNetworkError(
                ConnectException("refused").apply { initCause(SSLException("generic ssl failure")) }
            )
        )
    }

    @Test
    fun sslPeerUnverifiedUnderCancellationStopsReconnect() {
        val cancelled = CancellationException("job cancelled")
        cancelled.initCause(SSLPeerUnverifiedException("peer not authenticated"))
        assertTrue(isNonRecoverableWebSocketNetworkError(cancelled))
    }

    @Test
    fun sslKeyExceptionUnderThreeIoLayersStopsReconnect() {
        assertTrue(
            isNonRecoverableWebSocketNetworkError(
                IOException("l1", IOException("l2", IOException("l3", SSLKeyException("bad key"))))
            )
        )
    }

    @Test
    fun sslProtocolExceptionUnderTwoIoLayersStopsReconnect() {
        assertTrue(
            isNonRecoverableWebSocketNetworkError(
                IOException("l1", IOException("l2", SSLProtocolException("protocol error")))
            )
        )
    }

    @Test
    fun runtimeWrapperAroundSocketTimeoutRemainsRecoverable() {
        assertFalse(
            isNonRecoverableWebSocketNetworkError(
                RuntimeException("wrap", SocketTimeoutException("read timed out"))
            )
        )
    }
}
