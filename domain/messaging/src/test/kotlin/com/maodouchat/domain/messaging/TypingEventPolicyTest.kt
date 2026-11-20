package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypingEventPolicyTest {

    @Test
    fun `typing event ignored when no active chat`() {
        assertFalse(shouldApplyTypingEvent("", "chat-a"))
    }

    @Test
    fun `typing event only applies to active chat`() {
        assertTrue(shouldApplyTypingEvent("chat-a", "chat-a"))
        assertFalse(shouldApplyTypingEvent("chat-a", "chat-b"))
        assertFalse(shouldApplyTypingEvent("", ""))
        assertEquals(3_000L, REMOTE_TYPING_TIMEOUT_MS)
    }

    @Test
    fun `whitespace-only active chat ignores matching typing event`() {
        assertFalse(shouldApplyTypingEvent("   ", "   "))
        assertFalse(shouldApplyTypingEvent("chat-a", "   "))
    }

    @Test
    fun `typing event with trailing space does not match`() {
        assertFalse(shouldApplyTypingEvent("chat-a", "chat-a "))
        assertFalse(shouldApplyTypingEvent("chat-a ", "chat-a"))
    }

    @Test
    fun typingEventWithTrailingNewlineDoesNotMatch() {
        assertFalse(shouldApplyTypingEvent("chat-a", "chat-a\n"))
    }

    @Test
    fun typingEventMatchIsCaseSensitive() {
        assertFalse(shouldApplyTypingEvent("Chat-A", "chat-a"))
        assertFalse(shouldApplyTypingEvent("chat-a", "Chat-A"))
    }
}
