package com.maodouchat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledMessagePolicyTest {
    @Test
    fun `text normalize and validate`() {
        assertEquals("hi", ScheduledMessagePolicy.normalizeText("  hi  "))
        assertFalse(ScheduledMessagePolicy.isValidText("   "))
        assertTrue(ScheduledMessagePolicy.isValidText("ok"))
        // 超长文本先截断到 MAX_TEXT_LENGTH，截断后仍合法
        assertEquals(
            ScheduledMessagePolicy.MAX_TEXT_LENGTH,
            ScheduledMessagePolicy.normalizeText("x".repeat(4001)).length
        )
        assertTrue(ScheduledMessagePolicy.isValidText("x".repeat(4001)))
    }

    @Test
    fun `sendAt window`() {
        val now = 1_000_000L
        assertFalse(ScheduledMessagePolicy.isValidSendAt(now + 10_000L, now))
        assertTrue(ScheduledMessagePolicy.isValidSendAt(now + 60_000L, now))
        assertTrue(ScheduledMessagePolicy.isValidSendAt(now + 3_600_000L, now))
        assertFalse(ScheduledMessagePolicy.isValidSendAt(now + ScheduledMessagePolicy.MAX_DELAY_MS + 1, now))
        assertEquals(now + 60_000L, ScheduledMessagePolicy.clampSendAt(now + 1_000L, now))
    }

    @Test
    fun `pending cap`() {
        assertTrue(ScheduledMessagePolicy.canAddMore(0))
        assertTrue(ScheduledMessagePolicy.canAddMore(19))
        assertTrue(ScheduledMessagePolicy.canAddMore(20))
    }
}
