package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallLogEntry
import com.maodouchat.core.model.CallLogRow
import com.maodouchat.core.model.Direction
import com.maodouchat.core.model.MissedCall
import com.maodouchat.core.model.State
import kotlin.test.Test
import kotlin.test.assertEquals

class MissedCallRowsPolicyTest {

    private fun entry(id: String, at: Long, video: Boolean = false) =
        CallLogEntry(id = id, peerId = "p-$id", peerName = "Peer $id",
            isVideo = video, direction = Direction.OUTGOING,
            state = State.ANSWERED, startedAt = at, durationMs = 1000L)

    private fun missed(id: String, at: Long, callType: String = "AUDIO") =
        MissedCall(id = id, callerId = "c-$id", callerName = "Caller $id",
            callType = callType, receivedAt = at)

    @Test
    fun `store log wins on duplicate id`() {
        val rows = MissedCallRowsPolicy.mergeRows(
            listOf(entry("x", 300L)),
            listOf(missed("x", 200L))
        )
        assertEquals(listOf("x"), rows.map { it.id })
        assertEquals(Direction.OUTGOING, rows[0].direction)
        assertEquals(State.ANSWERED, rows[0].state)
    }

    @Test
    fun `missed calls map video flag case-insensitively and missed state`() {
        val rows = MissedCallRowsPolicy.mergeRows(
            emptyList(),
            listOf(missed("a", 100L, "VIDEO"), missed("b", 200L, "video"))
        )
        assertEquals(listOf(true, true), rows.map { it.video })
        assertEquals(listOf(Direction.INCOMING, Direction.INCOMING),
            rows.map { it.direction })
        assertEquals(listOf(State.MISSED, State.MISSED), rows.map { it.state })
        assertEquals(listOf(0L, 0L), rows.map { it.durationMs })
    }

    @Test
    fun `rows sort by time descending across both sources`() {
        val rows = MissedCallRowsPolicy.mergeRows(
            listOf(entry("s1", 100L)),
            listOf(missed("m1", 300L), missed("m2", 200L))
        )
        assertEquals(listOf("m1", "m2", "s1"), rows.map { it.id })
    }

    @Test
    fun `duplicate missed ids keep the first occurrence`() {
        val rows = MissedCallRowsPolicy.mergeRows(
            emptyList(),
            listOf(missed("d", 100L), missed("d", 500L))
        )
        assertEquals(listOf("d"), rows.map { it.id })
        assertEquals(100L, rows[0].at)
    }

    @Test
    fun `empty inputs stay empty`() {
        assertEquals(emptyList<CallLogRow>(),
            MissedCallRowsPolicy.mergeRows(emptyList(), emptyList()))
    }
}
