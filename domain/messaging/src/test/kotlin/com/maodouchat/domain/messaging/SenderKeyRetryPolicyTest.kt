package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException

class SenderKeyRetryPolicyTest {

    @Test
    fun `transient network errors detected`() {
        assertTrue(SenderKeyRetryPolicy.isTransientNetworkError(IOException("boom")))
        assertTrue(SenderKeyRetryPolicy.isTransientNetworkError(SocketTimeoutException()))
        assertTrue(SenderKeyRetryPolicy.isTransientNetworkError(IllegalStateException("Network unreachable")))
        assertTrue(SenderKeyRetryPolicy.isTransientNetworkError(IllegalStateException("request Timeout")))
        assertTrue(SenderKeyRetryPolicy.isTransientNetworkError(IllegalStateException("host unreachable")))
        assertTrue(SenderKeyRetryPolicy.isTransientNetworkError(IllegalStateException("canceled by user")))
        assertFalse(SenderKeyRetryPolicy.isTransientNetworkError(IllegalStateException("protocol error")))
        assertFalse(SenderKeyRetryPolicy.isTransientNetworkError(IllegalStateException()))
    }

    @Test
    fun `max attempts differ by error class`() {
        assertEquals(8, SenderKeyRetryPolicy.maxAttempts(true))
        assertEquals(5, SenderKeyRetryPolicy.maxAttempts(false))
    }

    @Test
    fun `should abandon at cap boundaries`() {
        assertFalse(SenderKeyRetryPolicy.shouldAbandon(4, false))
        assertTrue(SenderKeyRetryPolicy.shouldAbandon(5, false))
        assertFalse(SenderKeyRetryPolicy.shouldAbandon(7, true))
        assertTrue(SenderKeyRetryPolicy.shouldAbandon(8, true))
    }

    @Test
    fun `retry delay grows exponentially and caps`() {
        // jitterFactor 0.5 时抖动为 0，延迟即指数退避值
        assertEquals(10_000L, SenderKeyRetryPolicy.retryDelayMs(1, true, 0.5))
        assertEquals(20_000L, SenderKeyRetryPolicy.retryDelayMs(2, true, 0.5))
        assertEquals(40_000L, SenderKeyRetryPolicy.retryDelayMs(3, true, 0.5))
        assertEquals(30_000L, SenderKeyRetryPolicy.retryDelayMs(1, false, 0.5))
        assertEquals(1_800_000L, SenderKeyRetryPolicy.retryDelayMs(10, false, 0.5))
        assertEquals(300_000L, SenderKeyRetryPolicy.retryDelayMs(10, true, 0.5))
        // 抖动 ±25%，下限不低于 base 的一半
        assertEquals(7_500L, SenderKeyRetryPolicy.retryDelayMs(1, true, 0.0))
        assertEquals(12_500L, SenderKeyRetryPolicy.retryDelayMs(1, true, 1.0))
    }
}
