package com.maodouchat.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalSessionPolicyTest {

    @Test
    fun selfDeviceIsNeverEstablished() {
        assertFalse(SignalSessionPolicy.shouldEstablishSession("me", 2, "me", 2))
        assertTrue(SignalSessionPolicy.shouldEstablishSession("peer", 2, "me", 2))
        assertTrue(SignalSessionPolicy.shouldEstablishSession("peer", 1, null, 1))
    }

    @Test
    fun reestablishmentOrMissingSessionNeedsEnsure() {
        assertTrue(SignalSessionPolicy.shouldEnsureSession(true, true))
        assertTrue(SignalSessionPolicy.shouldEnsureSession(false, false))
        assertFalse(SignalSessionPolicy.shouldEnsureSession(true, false))
    }

    @Test
    fun discoveryWinsButEmptyDiscoveryKeepsDurableSessions() {
        assertEquals(
            listOf(1, 3),
            SignalSessionPolicy.candidateDeviceIds(listOf(3, 1), listOf(2)),
        )
        assertEquals(
            listOf(2, 4),
            SignalSessionPolicy.candidateDeviceIds(null, listOf(4, 2, 0, 300)),
        )
        assertTrue(
            SignalSessionPolicy.candidateDeviceIds(emptyList(), emptyList()).isEmpty(),
        )
    }
}
