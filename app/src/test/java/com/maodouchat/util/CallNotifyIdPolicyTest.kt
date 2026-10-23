package com.maodouchat.util

import com.maodouchat.notification.NotificationSlotPolicy
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

    @Test
    fun productionIncomingAndMissedIdsStayIsolated() {
        val callId = "sig-call-42"
        assertNotEquals(
            NotificationSlotPolicy.incomingCallNotifyId(callId),
            NotificationSlotPolicy.missedCallNotifyId(callId)
        )
    }

    @Test
    fun missedSaltIsPinnedToMissAscii() {
        assertEquals(0x4D495353, NotificationSlotPolicy.MISSED_CALL_ID_SALT)
        val callId = "sig-call-42"
        assertEquals(
            callId.hashCode() xor 0x4D495353,
            NotificationSlotPolicy.missedCallNotifyId(callId)
        )
    }

    @Test
    fun missedIdsAreDistinctAcrossCalls() {
        val ids = listOf("a", "b", "sig-call-42").map { NotificationSlotPolicy.missedCallNotifyId(it) }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun callAndMissedDataUrisAreNamespaced() {
        assertEquals(
            "maodouchat-notify://incoming/x",
            NotificationSlotPolicy.incomingCallDataUri("x")
        )
        assertEquals(
            "maodouchat-notify://missed/x",
            NotificationSlotPolicy.missedCallDataUri("x")
        )
        assertNotEquals(
            NotificationSlotPolicy.incomingCallDataUri("x"),
            NotificationSlotPolicy.missedCallDataUri("x")
        )
    }

    @Test
    fun callAndMissedTagsArePinned() {
        assertEquals("maodouchat_call", NotificationSlotPolicy.CALL_TAG)
        assertEquals("maodouchat_missed", NotificationSlotPolicy.MISSED_CALL_TAG)
    }
}
