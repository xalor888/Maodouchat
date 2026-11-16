package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScheduledMessageRetryPolicyTest {

    @Test
    fun `below five attempts keeps retrying`() {
        assertFalse(ScheduledMessageRetryPolicy.shouldAbandon(0))
        assertFalse(ScheduledMessageRetryPolicy.shouldAbandon(4))
    }

    @Test
    fun `at five attempts abandons`() {
        assertTrue(ScheduledMessageRetryPolicy.shouldAbandon(5))
        assertTrue(ScheduledMessageRetryPolicy.shouldAbandon(6))
    }
}
