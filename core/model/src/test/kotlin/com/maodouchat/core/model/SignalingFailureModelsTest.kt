package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignalingFailureModelsTest {

    @Test
    fun `failure operations keep declaration order`() {
        assertEquals(
            listOf(
                FailureOperation.SEND,
                FailureOperation.FETCH,
                FailureOperation.HANG_UP,
            ),
            FailureOperation.entries.toList(),
        )
    }

    @Test
    fun `valueOf round-trips every operation`() {
        FailureOperation.entries.forEach {
            assertEquals(it, FailureOperation.valueOf(it.name))
        }
    }

    @Test
    fun `signaling exception message names the operation in lowercase`() {
        val err = SignalingException(FailureOperation.HANG_UP)
        assertEquals("WebRTC signaling hang_up failed", err.message)
        assertEquals(FailureOperation.HANG_UP, err.operation)
        assertNull(err.statusCode)
        assertNull(err.code)
        assertNull(err.retryAfterSeconds)
    }

    @Test
    fun `signaling exception keeps status code and retry hint`() {
        val err = SignalingException(
            FailureOperation.FETCH,
            statusCode = 429,
            code = "CALL_INVITE_RATE_LIMITED",
            retryAfterSeconds = 60,
        )
        assertEquals(429, err.statusCode)
        assertEquals("CALL_INVITE_RATE_LIMITED", err.code)
        assertEquals(60L, err.retryAfterSeconds)
    }
}
