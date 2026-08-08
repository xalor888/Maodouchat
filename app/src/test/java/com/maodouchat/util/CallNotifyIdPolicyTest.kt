package com.maodouchat.util

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertEquals

/**
 * Incoming vs missed trays must not share NotificationManager ids, otherwise
 * cancelIncomingCall after MissedCallRecorder would erase the missed shade entry.
 */
class CallNotifyIdPolicyTest {

    private val missedSalt = 0x4D495353

    private fun incomingId(callId: String): Int = callId.hashCode()
    private fun missedId(callId: String): Int = callId.hashCode() xor missedSalt

    @Test
    fun incomingAndMissedIdsDifferForSameCall() {
        val callId = "sig-call-42"
        assertNotEquals(incomingId(callId), missedId(callId))
    }

    @Test
    fun stableAcrossCalls() {
        assertEquals(incomingId("a"), incomingId("a"))
        assertEquals(missedId("a"), missedId("a"))
        assertNotEquals(missedId("a"), missedId("b"))
    }
}
