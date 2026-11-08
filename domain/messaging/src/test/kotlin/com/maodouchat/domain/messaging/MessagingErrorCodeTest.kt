package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class MessagingErrorCodeTest {

    @Test
    fun `short message passes through`() {
        assertEquals("boom", messagingErrorCode(IllegalStateException("boom")))
    }

    @Test
    fun `long message is truncated to 120 chars`() {
        val long = "x".repeat(200)
        assertEquals("x".repeat(120), messagingErrorCode(IllegalStateException(long)))
    }

    @Test
    fun `exactly 120 chars is unchanged`() {
        val exact = "y".repeat(120)
        assertEquals(exact, messagingErrorCode(IllegalStateException(exact)))
    }

    @Test
    fun `blank message falls back to class name`() {
        assertEquals(
            "IllegalStateException",
            messagingErrorCode(IllegalStateException("   ")),
        )
    }

    @Test
    fun `missing message falls back to class name`() {
        assertEquals(
            "IllegalArgumentException",
            messagingErrorCode(IllegalArgumentException()),
        )
    }
}
