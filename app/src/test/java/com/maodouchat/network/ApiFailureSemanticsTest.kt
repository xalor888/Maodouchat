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
}
