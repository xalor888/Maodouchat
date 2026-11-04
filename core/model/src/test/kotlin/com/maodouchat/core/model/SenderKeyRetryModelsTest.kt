package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderKeyRetryModelsTest {

    @Test
    fun `transient failure is an IO exception with cause`() {
        val cause = RuntimeException("boom")
        val e = TransientCoverageException("net down", cause)
        assertTrue(e is java.io.IOException)
        assertEquals("net down", e.message)
        assertEquals(cause, e.cause)
    }

    @Test
    fun `coverage pending carries chat and epoch`() {
        val e = SenderKeyCoveragePendingException("c1", 42L)
        assertTrue(e is IllegalStateException)
        assertEquals("c1", e.chatId)
        assertEquals(42L, e.epoch)
    }

    @Test
    fun `retry view round-trips fields`() {
        val v = SenderKeyRetryView("c9", 3, 2, "timeout", 7L)
        assertEquals("c9", v.chatId)
        assertEquals(3, v.pendingCount)
        assertEquals(2, v.attempts)
        assertEquals("timeout", v.lastError)
        assertEquals(7L, v.epoch)
    }
}
