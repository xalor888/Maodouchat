package com.maodouchat.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
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
}
