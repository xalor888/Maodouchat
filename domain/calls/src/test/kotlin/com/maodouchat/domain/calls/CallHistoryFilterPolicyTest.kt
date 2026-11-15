package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallLogEntry
import com.maodouchat.core.model.Direction
import com.maodouchat.core.model.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallHistoryFilterPolicyTest {

    private fun entry(id: String, peerId: String, peerName: String, state: State) =
        CallLogEntry(
            id = id, peerId = peerId, peerName = peerName,
            isVideo = false, direction = Direction.INCOMING,
            state = state, startedAt = 0L,
        )

    private val logs = listOf(
        entry("c1", "u1", "Alice", State.ANSWERED),
        entry("c2", "u2", "Bob", State.MISSED),
        entry("c3", "u3", "alice-zhang", State.MISSED),
    )

    @Test
    fun `only missed keeps missed only`() {
        assertEquals(listOf(logs[1], logs[2]), CallHistoryFilterPolicy.filter(logs, "", true))
    }

    @Test
    fun `all logs when only missed is false`() {
        assertEquals(logs, CallHistoryFilterPolicy.filter(logs, "", false))
    }

    @Test
    fun `blank query returns base unchanged`() {
        assertEquals(listOf(logs[1], logs[2]), CallHistoryFilterPolicy.filter(logs, "   ", true))
    }

    @Test
    fun `matches by peer name case-insensitively`() {
        assertTrue(CallHistoryFilterPolicy.matches(logs[0], "ali"))
        assertEquals(listOf(logs[0], logs[2]), CallHistoryFilterPolicy.filter(logs, "ALICE", false))
    }

    @Test
    fun `matches by peer id`() {
        assertEquals(listOf(logs[1]), CallHistoryFilterPolicy.filter(logs, "u2", false))
    }

    @Test
    fun `only missed combines with query`() {
        assertEquals(listOf(logs[2]), CallHistoryFilterPolicy.filter(logs, "alice", true))
    }

    @Test
    fun `no match returns empty`() {
        assertEquals(emptyList<CallLogEntry>(), CallHistoryFilterPolicy.filter(logs, "zzz", false))
        assertFalse(CallHistoryFilterPolicy.matches(logs[0], "zzz"))
    }
}
