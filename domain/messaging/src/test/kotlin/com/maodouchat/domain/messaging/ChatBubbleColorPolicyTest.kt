package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChatBubbleColorPolicyTest {

    @Test
    fun `all six ids round-trip`() {
        listOf("blue", "green", "purple", "orange", "pink", "teal").forEach {
            assertEquals(it, ChatBubbleColorPolicy.normalize(it))
        }
    }

    @Test
    fun `uppercase and padding are normalized`() {
        assertEquals("blue", ChatBubbleColorPolicy.normalize("  BLUE "))
        assertEquals("green", ChatBubbleColorPolicy.normalize("Green"))
    }

    @Test
    fun `null blank and unknown ids fall back to green`() {
        assertEquals("green", ChatBubbleColorPolicy.normalize(null))
        assertEquals("green", ChatBubbleColorPolicy.normalize("   "))
        assertEquals("green", ChatBubbleColorPolicy.normalize("red"))
        assertEquals("green", ChatBubbleColorPolicy.normalize("blueish"))
    }
}
