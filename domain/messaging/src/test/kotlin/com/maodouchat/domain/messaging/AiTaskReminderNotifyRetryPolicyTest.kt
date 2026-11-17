package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiTaskReminderNotifyRetryPolicyTest {

    @Test
    fun `retries below three attempts`() {
        assertTrue(AiTaskReminderNotifyRetryPolicy.shouldRetryNotify(0))
        assertTrue(AiTaskReminderNotifyRetryPolicy.shouldRetryNotify(1))
        assertTrue(AiTaskReminderNotifyRetryPolicy.shouldRetryNotify(2))
    }

    @Test
    fun `fails at three retries and beyond`() {
        assertFalse(AiTaskReminderNotifyRetryPolicy.shouldRetryNotify(3))
        assertFalse(AiTaskReminderNotifyRetryPolicy.shouldRetryNotify(4))
        assertFalse(AiTaskReminderNotifyRetryPolicy.shouldRetryNotify(100))
    }

    @Test
    fun `retry is fifteen minutes later`() {
        assertEquals(1_000_000L + 15L * 60L * 1000L, AiTaskReminderNotifyRetryPolicy.nextRetryAt(1_000_000L))
    }

    @Test
    fun `constants are three retries and fifteen minutes`() {
        assertEquals(3, AiTaskReminderNotifyRetryPolicy.MAX_NOTIFY_RETRIES)
        assertEquals(900_000L, AiTaskReminderNotifyRetryPolicy.RETRY_DELAY_MS)
    }
}
