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

    @Test
    fun `whitespace-only owner never emits`() {
        assertFalse(TypingSessionPolicy.mayEmit("   ", "t", "   "))
        assertFalse(TypingSessionPolicy.mayEmit("", "t", "u1"))
    }

    @Test
    fun `owner with internal spaces is not the me placeholder`() {
        assertTrue(TypingSessionPolicy.mayEmit("m e", "t", "m e"))
        assertTrue(TypingSessionPolicy.mayEmit("me2", "t", "me2"))
    }

    @Test
    fun `announce start compares chat ids without trimming`() {
        assertTrue(TypingSessionPolicy.shouldAnnounceStart(" c1 ", "c1"))
        assertFalse(TypingSessionPolicy.shouldAnnounceStart(" c1 ", " c1 "))
    }

    @Test
    fun `announce start still announces when already announced is blank`() {
        assertTrue(TypingSessionPolicy.shouldAnnounceStart("c1", ""))
        assertTrue(TypingSessionPolicy.shouldAnnounceStart("c1", "   "))
    }

    @Test
    fun `emit requires the live id to match the trimmed owner exactly`() {
        assertFalse(TypingSessionPolicy.mayEmit(" u1 ", "t", " u1 "))
        assertFalse(TypingSessionPolicy.mayEmit("u1", "t", "u1 "))
    }

    @Test
    fun `blank token with spaces never emits`() {
        assertFalse(TypingSessionPolicy.mayEmit("u1", "   ", "u1"))
        assertFalse(TypingSessionPolicy.mayEmit("u1", "\t", "u1"))
    }

    @Test
    fun `me placeholder refuses regardless of live session`() {
        assertFalse(TypingSessionPolicy.mayEmit("me", "t", "u1"))
        assertFalse(TypingSessionPolicy.mayEmit("me", null, null))
        assertFalse(TypingSessionPolicy.mayEmit(" me ", "t", "u1"))
    }

    @Test
    fun `tab-trimmed owner still matches live id`() {
        assertTrue(TypingSessionPolicy.mayEmit("\tu1\t", "t", "u1"))
        assertFalse(TypingSessionPolicy.mayEmit("\tme\n", "t", "me"))
    }

    @Test
    fun `announce start with null active never announces`() {
        assertFalse(TypingSessionPolicy.shouldAnnounceStart(null, null))
        assertFalse(TypingSessionPolicy.shouldAnnounceStart("\t", null))
        assertTrue(TypingSessionPolicy.shouldAnnounceStart("c1", "c1 "))
    }

    @Test
    fun `live id case mismatch never emits`() {
        assertFalse(TypingSessionPolicy.mayEmit("u1", "t", "U1"))
        assertFalse(TypingSessionPolicy.mayEmit("U1", "t", "u1"))
        assertTrue(TypingSessionPolicy.mayEmit("u1", "t", "u1"))
    }
}
