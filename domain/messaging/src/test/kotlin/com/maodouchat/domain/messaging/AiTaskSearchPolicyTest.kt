package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiTaskSearchPolicyTest {

    @Test
    fun `matches title`() {
        assertTrue(AiTaskSearchPolicy.matches("Buy milk", null, null, "remind me", "MILK"))
    }

    @Test
    fun `matches owner case-insensitively`() {
        assertTrue(AiTaskSearchPolicy.matches("Call", "Alice", null, "call mom", "ali"))
    }

    @Test
    fun `matches due text`() {
        assertTrue(AiTaskSearchPolicy.matches("Call", null, "Tomorrow 9:00", "call", "tomorrow"))
    }

    @Test
    fun `matches source query`() {
        assertTrue(AiTaskSearchPolicy.matches("Call", null, null, "remind me to call", "remind"))
    }

    @Test
    fun `null owner and due text do not match`() {
        assertFalse(AiTaskSearchPolicy.matches("Call", null, null, "call", "zzz"))
    }
}
