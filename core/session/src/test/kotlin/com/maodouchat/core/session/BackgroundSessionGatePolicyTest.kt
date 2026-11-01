package com.maodouchat.core.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundSessionGatePolicyTest {

    @Test
    fun stableSessionContinues() {
        assertTrue(BackgroundSessionGatePolicy.mayContinue("u1", "tok", "u1", false))
    }

    @Test
    fun purgeInProgressAborts() {
        assertFalse(BackgroundSessionGatePolicy.mayContinue("u1", "tok", "u1", true))
    }

    @Test
    fun logoutOrSwitchAborts() {
        assertFalse(BackgroundSessionGatePolicy.mayContinue("u1", null, "u1", false))
        assertFalse(BackgroundSessionGatePolicy.mayContinue("u1", "", "u1", false))
        assertFalse(BackgroundSessionGatePolicy.mayContinue("u1", "tok", null, false))
        assertFalse(BackgroundSessionGatePolicy.mayContinue("u1", "tok", "u2", false))
        assertFalse(BackgroundSessionGatePolicy.mayContinue("", "tok", "u1", false))
    }

    @Test
    fun blankValuesAbort() {
        assertFalse(BackgroundSessionGatePolicy.mayContinue("  ", "tok", "  ", false))
        assertFalse(BackgroundSessionGatePolicy.mayContinue("u1", "   ", "u1", false))
        assertFalse(BackgroundSessionGatePolicy.mayContinue("u1", "tok", "   ", false))
        assertFalse(BackgroundSessionGatePolicy.mayContinue("u1", "tok", " u1", false))
    }

    @Test
    fun userIdComparisonIsExactAndCaseSensitive() {
        assertFalse(BackgroundSessionGatePolicy.mayContinue("u1", "tok", "U1", false))
        assertFalse(BackgroundSessionGatePolicy.mayContinue("U1", "tok", "u1", false))
        assertFalse(BackgroundSessionGatePolicy.mayContinue("u1", "tok", "u1 ", false))
    }

    @Test
    fun tokenValueIsNeverComparedOnlyItsPresence() {
        assertTrue(BackgroundSessionGatePolicy.mayContinue("u1", "tok-a", "u1", false))
        assertTrue(BackgroundSessionGatePolicy.mayContinue("u1", "completely-different-token", "u1", false))
        assertTrue(BackgroundSessionGatePolicy.mayContinue("u1", "  padded  ", "u1", false))
    }
}
