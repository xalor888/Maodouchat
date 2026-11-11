package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallLogEntry
import com.maodouchat.core.model.CallType
import com.maodouchat.core.model.CallUiState
import com.maodouchat.core.model.Direction
import com.maodouchat.core.model.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallLogWriterTest {

    private class Fixture {
        var now = 5_000L
        var callId = "call-1"
        var ownerId = "u1"
        var state = CallUiState(
            contactId = "u2",
            contactName = "Bob",
            callType = CallType.VIDEO,
            isIncoming = true,
        )
        val stored = mutableListOf<CallLogEntry>()
        val upserts = mutableListOf<Pair<CallLogEntry, String?>>()

        fun writer() = CallLogWriter(
            listCallLogs = { peerId -> stored.filter { it.peerId == peerId } },
            upsertCallLog = { entry, expected -> upserts += entry to expected },
            currentState = { state },
            activeCallId = { callId },
            callLogOwnerUserId = { ownerId },
            nowMillis = { now },
        )
    }

    private fun storedEntry(state: State, startedAt: Long = 1_000L, durationMs: Long = 100L) =
        CallLogEntry(
            id = "call-1",
            peerId = "u2",
            peerName = "Bob",
            isVideo = true,
            direction = Direction.INCOMING,
            state = state,
            startedAt = startedAt,
            durationMs = durationMs,
            isGroup = false,
        )

    @Test
    fun blankCallIdDoesNothing() {
        val f = Fixture()
        f.callId = ""
        f.writer().writeCallLog(State.ANSWERED)

        assertTrue(f.upserts.isEmpty())
    }

    @Test
    fun blankPeerDoesNothing() {
        val f = Fixture()
        f.state = f.state.copy(contactId = "")
        f.writer().writeCallLog(State.ANSWERED)

        assertTrue(f.upserts.isEmpty())
    }

    @Test
    fun newEntryWritesStartedAtNowWithZeroDuration() {
        val f = Fixture()
        f.writer().writeCallLog(State.MISSED)

        val (entry, expected) = f.upserts.single()
        assertEquals("call-1", entry.id)
        assertEquals("u2", entry.peerId)
        assertEquals("Bob", entry.peerName)
        assertTrue(entry.isVideo)
        assertEquals(Direction.INCOMING, entry.direction)
        assertEquals(State.MISSED, entry.state)
        assertEquals(5_000L, entry.startedAt)
        assertEquals(0L, entry.durationMs)
        assertEquals("u1", expected)
    }

    @Test
    fun blankContactNameFallsBackToPeerId() {
        val f = Fixture()
        f.state = f.state.copy(contactName = "")
        f.writer().writeCallLog(State.MISSED)

        assertEquals("u2", f.upserts.single().first.peerName)
    }

    @Test
    fun answeredOverMissedResetsStartedAt() {
        val f = Fixture()
        f.stored += storedEntry(State.MISSED)
        f.writer().writeCallLog(State.ANSWERED)

        val entry = f.upserts.single().first
        assertEquals(State.ANSWERED, entry.state)
        assertEquals(5_000L, entry.startedAt)
        assertEquals(100L, entry.durationMs)
    }

    @Test
    fun answeredOverAnsweredAccumulatesDuration() {
        val f = Fixture()
        f.stored += storedEntry(State.ANSWERED)
        f.writer().writeCallLog(State.ANSWERED)

        val entry = f.upserts.single().first
        assertEquals(1_000L, entry.startedAt)
        assertEquals(4_000L, entry.durationMs)
    }

    @Test
    fun missedOverAnsweredKeepsStartedAt() {
        val f = Fixture()
        f.stored += storedEntry(State.ANSWERED)
        f.writer().writeCallLog(State.MISSED)

        val entry = f.upserts.single().first
        assertEquals(State.MISSED, entry.state)
        assertEquals(1_000L, entry.startedAt)
        assertEquals(100L, entry.durationMs)
    }

    @Test
    fun outgoingAudioGroupEntry() {
        val f = Fixture()
        f.state = f.state.copy(isIncoming = false, callType = CallType.AUDIO, isGroupCall = true)
        f.writer().writeCallLog(State.ANSWERED)

        val entry = f.upserts.single().first
        assertEquals(Direction.OUTGOING, entry.direction)
        assertEquals(false, entry.isVideo)
        assertTrue(entry.isGroup)
    }

    @Test
    fun blankOwnerPassesNullExpectedUserId() {
        val f = Fixture()
        f.ownerId = ""
        f.writer().writeCallLog(State.MISSED)

        assertEquals(null, f.upserts.single().second)
    }
}
