package com.maodouchat.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ApiFailureSemanticsTest {
    @Test
    fun dnsAndConnectionFailuresAreSafeToRetry() {
        assertFalse(apiExceptionForIOException(UnknownHostException()).requestMayHaveReachedServer)
        assertFalse(apiExceptionForIOException(ConnectException()).requestMayHaveReachedServer)
    }

    @Test
    fun timeoutAndGenericIoHaveUnknownOutcome() {
        assertTrue(apiExceptionForIOException(SocketTimeoutException()).requestMayHaveReachedServer)
        assertTrue(apiExceptionForIOException(IOException("response body closed")).requestMayHaveReachedServer)
    }

    @Test
    fun wrappedConnectionFailureRemainsSafeToRetry() {
        assertFalse(
            apiExceptionForIOException(IOException("route failed", ConnectException())).requestMayHaveReachedServer
        )
    }

    @Test
    fun noRouteToHostIsSafeToRetry() {
        val mapped = apiExceptionForIOException(NoRouteToHostException())
        assertEquals(ApiFailureKind.NETWORK, mapped.kind)
        assertFalse(mapped.requestMayHaveReachedServer)
    }

    @Test
    fun timeoutWithUnestablishedConnectionStaysSafe() {
        val timeout = SocketTimeoutException("timed out").apply { initCause(ConnectException()) }
        val mapped = apiExceptionForIOException(timeout)
        assertEquals(ApiFailureKind.TIMEOUT, mapped.kind)
        assertFalse(mapped.requestMayHaveReachedServer)
    }

    @Test
    fun deeplyNestedDnsFailureIsSafeToRetry() {
        val nested = IOException("level1", IOException("level2", UnknownHostException()))
        assertFalse(apiExceptionForIOException(nested).requestMayHaveReachedServer)
    }

    @Test
    fun genericIoMapsToNetworkKind() {
        assertEquals(ApiFailureKind.NETWORK, apiExceptionForIOException(IOException("x")).kind)
        assertEquals(ApiFailureKind.TIMEOUT, apiExceptionForIOException(SocketTimeoutException("t")).kind)
    }

    @Test
    fun originalCauseChainIsPreserved() {
        val io = IOException("boom")
        assertSame(io, apiExceptionForIOException(io).cause)
    }

    @Test
    fun timeoutNestedUnderDnsFailureIsStillTimeoutKind() {
        val timeout = SocketTimeoutException("t").apply { initCause(UnknownHostException()) }
        val mapped = apiExceptionForIOException(timeout)
        assertEquals(ApiFailureKind.TIMEOUT, mapped.kind)
        assertFalse(mapped.requestMayHaveReachedServer)
    }

    @Test
    fun sslFailureHasUnknownOutcome() {
        val ssl = IOException("s", javax.net.ssl.SSLException("handshake"))
        val mapped = apiExceptionForIOException(ssl)
        assertEquals(ApiFailureKind.NETWORK, mapped.kind)
        assertTrue(mapped.requestMayHaveReachedServer)
    }

    @Test
    fun noArgExceptionsMapByType() {
        assertEquals(ApiFailureKind.NETWORK, apiExceptionForIOException(IOException()).kind)
        assertEquals(ApiFailureKind.TIMEOUT, apiExceptionForIOException(SocketTimeoutException()).kind)
        assertTrue(apiExceptionForIOException(IOException()).requestMayHaveReachedServer)
    }

    @Test
    fun wrappedTimeoutKeepsNetworkKindTopLevelDecides() {
        val wrapped = IOException("w", SocketTimeoutException("t"))
        val mapped = apiExceptionForIOException(wrapped)
        assertEquals(ApiFailureKind.NETWORK, mapped.kind)
        assertTrue(mapped.requestMayHaveReachedServer)
    }

    @Test
    fun connectFailureWrappedTwiceIsSafeToRetry() {
        val wrapped = IOException("l1", IOException("l2", ConnectException()))
        assertFalse(apiExceptionForIOException(wrapped).requestMayHaveReachedServer)
    }

    @Test
    fun timeoutNestedUnderNoRouteIsStillTimeoutButSafe() {
        val timeout = SocketTimeoutException("t").apply { initCause(NoRouteToHostException()) }
        val mapped = apiExceptionForIOException(timeout)
        assertEquals(ApiFailureKind.TIMEOUT, mapped.kind)
        assertFalse(mapped.requestMayHaveReachedServer)
    }

    @Test
    fun directSslExceptionHasUnknownOutcome() {
        val mapped = apiExceptionForIOException(javax.net.ssl.SSLException("handshake"))
        assertEquals(ApiFailureKind.NETWORK, mapped.kind)
        assertTrue(mapped.requestMayHaveReachedServer)
    }

    @Test
    fun timeoutNestedUnderGenericIoHasUnknownOutcome() {
        val timeout = SocketTimeoutException("t").apply { initCause(IOException("generic")) }
        val mapped = apiExceptionForIOException(timeout)
        assertEquals(ApiFailureKind.TIMEOUT, mapped.kind)
        assertTrue(mapped.requestMayHaveReachedServer)
    }

    @Test
    fun noRouteWrappedTwiceIsSafeToRetry() {
        val wrapped = IOException("l1", IOException("l2", NoRouteToHostException()))
        val mapped = apiExceptionForIOException(wrapped)
        assertEquals(ApiFailureKind.NETWORK, mapped.kind)
        assertFalse(mapped.requestMayHaveReachedServer)
    }

    @Test
    fun wrappedCauseIsPreservedAsTopLevel() {
        val io = IOException("w", ConnectException())
        val mapped = apiExceptionForIOException(io)
        assertSame(io, mapped.cause)
        assertEquals(ApiFailureKind.NETWORK, mapped.kind)
        assertFalse(mapped.requestMayHaveReachedServer)
    }
}
