package com.maodouchat.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundSessionGateTest {
    @Test
    fun stableSessionContinues() {
        assertTrue(BackgroundSessionGate.mayContinue("u1", "tok", "u1"))
    }

    @Test
    fun logoutOrSwitchAborts() {
        assertFalse(BackgroundSessionGate.mayContinue("u1", null, "u1"))
        assertFalse(BackgroundSessionGate.mayContinue("u1", "", "u1"))
        assertFalse(BackgroundSessionGate.mayContinue("u1", "tok", null))
        assertFalse(BackgroundSessionGate.mayContinue("u1", "tok", "u2"))
        assertFalse(BackgroundSessionGate.mayContinue("", "tok", "u1"))
    }

    @Test
    fun rejectsBlankExpectedEvenIfLiveMatches() {
        assertFalse(BackgroundSessionGate.mayContinue("  ", "tok", "  "))
        assertFalse(BackgroundSessionGate.mayContinue("u1", "tok", " u1"))
    }

    @Test
    fun acceptsStableSessionAcrossCalls() {
        val owner = "owner-42"
        assertTrue(BackgroundSessionGate.mayContinue(owner, "access-a", owner))
        assertTrue(BackgroundSessionGate.mayContinue(owner, "access-b", owner))
        assertFalse(BackgroundSessionGate.mayContinue(owner, "access-b", "other"))
    }

}
