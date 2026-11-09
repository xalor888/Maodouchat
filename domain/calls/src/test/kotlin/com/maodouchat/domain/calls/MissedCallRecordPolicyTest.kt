package com.maodouchat.domain.calls

import com.maodouchat.core.model.Direction
import com.maodouchat.core.model.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MissedCallRecordPolicyTest {

    @Test
    fun `video maps to VIDEO and audio to AUDIO`() {
        val video = buildMissedCallRecord("m1", "u1", "Alice", true, 1000L)
        val audio = buildMissedCallRecord("m2", "u1", "Alice", false, 2000L)

        assertEquals("VIDEO", video.missedCall.callType)
        assertEquals("AUDIO", audio.missedCall.callType)
        assertTrue(video.callLogEntry.isVideo)
        assertFalse(audio.callLogEntry.isVideo)
    }

    @Test
    fun `blank caller name falls back to fromUserId in both records`() {
        val record = buildMissedCallRecord("m1", "u1", "   ", true, 1000L)

        assertEquals("u1", record.missedCall.callerName)
        assertEquals("u1", record.callLogEntry.peerName)
        assertEquals("u1", record.missedCall.callerId)
        assertEquals("u1", record.callLogEntry.peerId)
    }

    @Test
    fun `both records share id and timestamp`() {
        val record = buildMissedCallRecord("m1", "u1", "Alice", false, 1000L, isGroup = true)

        assertEquals("m1", record.missedCall.id)
        assertEquals("m1", record.callLogEntry.id)
        assertEquals(1000L, record.missedCall.receivedAt)
        assertEquals(1000L, record.callLogEntry.startedAt)
        assertEquals(Direction.INCOMING, record.callLogEntry.direction)
        assertEquals(State.MISSED, record.callLogEntry.state)
        assertEquals(0L, record.callLogEntry.durationMs)
        assertTrue(record.callLogEntry.isGroup)
        assertFalse(record.missedCall.isRead)
    }

    @Test
    fun `displayCallerName keeps non-blank names`() {
        assertEquals("Alice", displayCallerName("Alice", "u1"))
        assertEquals("u1", displayCallerName("", "u1"))
    }
}
