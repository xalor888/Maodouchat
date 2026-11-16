package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiTaskReminderRetryPolicyTest {

    @Test
    fun `retries below three attempts`() {
        assertTrue(AiTaskReminderRetryPolicy.shouldRetry(0))
        assertTrue(AiTaskReminderRetryPolicy.shouldRetry(1))
        assertTrue(AiTaskReminderRetryPolicy.shouldRetry(2))
    }

    @Test
    fun `fails at three attempts and beyond`() {
        assertFalse(AiTaskReminderRetryPolicy.shouldRetry(3))
        assertFalse(AiTaskReminderRetryPolicy.shouldRetry(4))
        assertFalse(AiTaskReminderRetryPolicy.shouldRetry(100))
    }
}
