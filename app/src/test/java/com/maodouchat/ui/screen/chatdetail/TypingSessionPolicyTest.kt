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

    @Test
    fun `blank token or user id never emits`() {
        assertFalse(TypingSessionPolicy.mayEmit("u1", null, "u1"))
        assertFalse(TypingSessionPolicy.mayEmit("u1", "", "u1"))
        assertFalse(TypingSessionPolicy.mayEmit("u1", "t", null))
        assertFalse(TypingSessionPolicy.mayEmit("u1", "t", "   "))
    }

    @Test
    fun `owner is trimmed before the me placeholder check`() {
        assertFalse(TypingSessionPolicy.mayEmit(" me ", "t", "me"))
        assertTrue(TypingSessionPolicy.mayEmit(" u1 ", "t", "u1"))
    }

    @Test
    fun `me placeholder check is case-sensitive`() {
        assertTrue(TypingSessionPolicy.mayEmit("ME", "t", "ME"))
    }

    @Test
    fun `announce start ignores blank chat ids`() {
        assertFalse(TypingSessionPolicy.shouldAnnounceStart("   ", null))
        assertFalse(TypingSessionPolicy.shouldAnnounceStart("   ", "c1"))
        assertFalse(TypingSessionPolicy.shouldAnnounceStart(null, "c1"))
    }

    @Test
    fun `emit and announce decisions are repeatable`() {
        repeat(10) { assertTrue(TypingSessionPolicy.mayEmit("u1", "t", "u1")) }
        repeat(10) { assertFalse(TypingSessionPolicy.shouldAnnounceStart("c1", "c1")) }
        repeat(10) { assertTrue(TypingSessionPolicy.shouldAnnounceStart("c2", "c1")) }
    }
}
