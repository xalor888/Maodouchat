package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CallLogModelsTest {

    @Test
    fun entry_holdsFieldsWithDefaults() {
        val entry = CallLogEntry("id1", "peer1", "Alice", true, Direction.OUTGOING, State.ANSWERED, 1700000000000L)
        assertEquals("id1", entry.id)
        assertEquals("peer1", entry.peerId)
        assertEquals("Alice", entry.peerName)
        assertEquals(0L, entry.durationMs)
        assertFalse(entry.isGroup)
    }

    @Test
    fun directionAndState_coverBothCases() {
        assertEquals(2, Direction.entries.size)
        assertEquals(2, State.entries.size)
        assertEquals(Direction.INCOMING, Direction.valueOf("INCOMING"))
        assertEquals(State.MISSED, State.valueOf("MISSED"))
    }

    @Test
    fun entry_acceptsExplicitDurationAndGroup() {
        val entry = CallLogEntry("id2", "peer2", "Bob", false, Direction.INCOMING, State.MISSED, 1700000000000L, 5000L, true)
        assertEquals(5000L, entry.durationMs)
        assertEquals(true, entry.isGroup)
    }
}
