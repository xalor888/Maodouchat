package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageReminderPolicyTest {

    @Test
    fun `quick delay presets are strictly increasing and within window`() {
        val delays = MessageReminderPolicy.QUICK_DELAYS_MS
        assertTrue(delays.zipWithNext().all { (a, b) -> a < b })
        assertTrue(delays.all { it in MessageReminderPolicy.MIN_DELAY_MS..MessageReminderPolicy.MAX_DELAY_MS })
    }

    @Test
    fun `window bounds match one minute to thirty days`() {
        assertEquals(60_000L, MessageReminderPolicy.MIN_DELAY_MS)
        assertEquals(30L * 24L * 60L * 60L * 1_000L, MessageReminderPolicy.MAX_DELAY_MS)
        assertEquals(60_000L, MessageReminderPolicy.QUICK_DELAYS_MS.first())
        assertEquals(7L * 24L * 60L * 60L * 1_000L, MessageReminderPolicy.QUICK_DELAYS_MS.last())
    }
}
