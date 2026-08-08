package com.maodouchat.ui.screen.chatdetail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingSessionPolicyTest {

    @Test
    fun rejectsPlaceholderAndSwitchedSessions() {
        assertFalse(TypingSessionPolicy.mayEmit(null, "t", "u1"))
        assertFalse(TypingSessionPolicy.mayEmit("me", "t", "me"))
        assertFalse(TypingSessionPolicy.mayEmit("u1", null, "u1"))
        assertFalse(TypingSessionPolicy.mayEmit("u1", "t", "u2"))
        assertTrue(TypingSessionPolicy.mayEmit("u1", "t", "u1"))
    }

    @Test
    fun announceStartOnlyOncePerChat() {
        assertTrue(TypingSessionPolicy.shouldAnnounceStart("c1", null))
        assertFalse(TypingSessionPolicy.shouldAnnounceStart("c1", "c1"))
        assertTrue(TypingSessionPolicy.shouldAnnounceStart("c2", "c1"))
        assertFalse(TypingSessionPolicy.shouldAnnounceStart("", null))
    }
}
