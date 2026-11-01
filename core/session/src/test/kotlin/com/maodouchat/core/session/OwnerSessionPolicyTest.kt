package com.maodouchat.core.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerSessionPolicyTest {

    private fun snapshot(ownerUserId: String = "u1", generation: Long = 7L) =
        OwnerSessionSnapshot(ownerUserId, generation)

    @Test
    fun currentSessionPasses() {
        assertTrue(OwnerSessionPolicy.isCurrent(snapshot(), "u1", "tok", 7L, false))
    }

    @Test
    fun purgeInProgressRejects() {
        assertFalse(OwnerSessionPolicy.isCurrent(snapshot(), "u1", "tok", 7L, true))
    }

    @Test
    fun blankOrPlaceholderOwnerRejects() {
        assertFalse(OwnerSessionPolicy.isCurrent(snapshot(""), "u1", "tok", 7L, false))
        assertFalse(OwnerSessionPolicy.isCurrent(snapshot("  "), "  ", "tok", 7L, false))
        assertFalse(OwnerSessionPolicy.isCurrent(snapshot("me"), "me", "tok", 7L, false))
    }

    @Test
    fun liveUserMismatchRejects() {
        assertFalse(OwnerSessionPolicy.isCurrent(snapshot(), "u2", "tok", 7L, false))
        assertFalse(OwnerSessionPolicy.isCurrent(snapshot(), null, "tok", 7L, false))
    }

    @Test
    fun blankTokenRejects() {
        assertFalse(OwnerSessionPolicy.isCurrent(snapshot(), "u1", null, 7L, false))
        assertFalse(OwnerSessionPolicy.isCurrent(snapshot(), "u1", "   ", 7L, false))
    }

    @Test
    fun generationMismatchRejects() {
        assertFalse(OwnerSessionPolicy.isCurrent(snapshot(), "u1", "tok", 8L, false))
    }

    @Test
    fun tokenValueIsNeverComparedOnlyItsPresence() {
        assertTrue(OwnerSessionPolicy.isCurrent(snapshot(), "u1", "another-token", 7L, false))
    }
}
