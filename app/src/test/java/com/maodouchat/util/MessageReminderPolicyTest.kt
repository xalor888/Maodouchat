package com.maodouchat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageReminderPolicyTest {

    @Test
    fun `quick delay presets are strictly increasing and within window`() {
        val delays = MessageReminderPolicy.QUICK_DELAYS_MS
        assertTrue(delays.isNotEmpty())
        delays.zipWithNext().forEach { (a, b) ->
            assertTrue("delays must increase: $a !< $b", a < b)
        }
        delays.forEach { delay ->
            assertTrue(delay >= MessageReminderPolicy.MIN_DELAY_MS)
            assertTrue(delay <= MessageReminderPolicy.MAX_DELAY_MS)
        }
    }

    @Test
    fun `window covers one minute to thirty days`() {
        assertEquals(60_000L, MessageReminderPolicy.MIN_DELAY_MS)
        assertEquals(30L * 24L * 60L * 60L * 1_000L, MessageReminderPolicy.MAX_DELAY_MS)
    }

    @Test
    fun `first preset is one minute`() {
        assertEquals(60_000L, MessageReminderPolicy.QUICK_DELAYS_MS.first())
    }

    @Test
    fun `last preset is seven days`() {
        assertEquals(7L * 24L * 60L * 60L * 1_000L, MessageReminderPolicy.QUICK_DELAYS_MS.last())
    }
}
