package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledMessagePolicyTest {

    @Test
    fun `send window is 60 seconds to 7 days`() {
        val now = 1_000_000L
        assertFalse(ScheduledMessagePolicy.isValidSendAt(now + 59_000L, now))
        assertTrue(ScheduledMessagePolicy.isValidSendAt(now + 60_000L, now))
        assertTrue(ScheduledMessagePolicy.isValidSendAt(now + ScheduledMessagePolicy.MAX_DELAY_MS, now))
        assertFalse(ScheduledMessagePolicy.isValidSendAt(now + ScheduledMessagePolicy.MAX_DELAY_MS + 1, now))
    }

    @Test
    fun `text is trimmed capped and validated`() {
        assertEquals("hi", ScheduledMessagePolicy.normalizeText("  hi  "))
        assertEquals(4000, ScheduledMessagePolicy.normalizeText("z".repeat(5000)).length)
        assertFalse(ScheduledMessagePolicy.isValidText("   "))
        assertTrue(ScheduledMessagePolicy.isValidText("hello"))
    }

    @Test
    fun `pending cap and delay clamp`() {
        assertTrue(ScheduledMessagePolicy.canAddMore(55))
        assertFalse(ScheduledMessagePolicy.canAddMore(56))
        assertEquals(0L, ScheduledMessagePolicy.delayFromNow(500L, 1_000L))
        assertEquals(60_000L, ScheduledMessagePolicy.QUICK_DELAYS_MS.first())
        assertEquals(7L * 24 * 60 * 60 * 1000, ScheduledMessagePolicy.QUICK_DELAYS_MS.last())
        assertEquals(60_000L, ScheduledMessagePolicy.clampSendAt(1_000L, 0L))
        assertEquals(120_000L, ScheduledMessagePolicy.clampSendAt(120_000L, 0L))
    }
}
