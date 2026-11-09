package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallLogEntry
import com.maodouchat.core.model.Direction
import com.maodouchat.core.model.State
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallLogPolicyTest {

    private fun entry(
        id: String = "c1",
        peerId: String = "u1",
        peerName: String = "Alice",
        isVideo: Boolean = false,
        direction: Direction = Direction.INCOMING,
        state: State = State.ANSWERED,
        startedAt: Long = 1000L,
        durationMs: Long = 5000L,
        isGroup: Boolean = false,
    ) = CallLogEntry(id, peerId, peerName, isVideo, direction, state, startedAt, durationMs, isGroup)

    @Test
    fun `round-trip preserves all fields`() {
        val original = entry(isVideo = true, direction = Direction.OUTGOING, state = State.MISSED, isGroup = true)

        assertEquals(listOf(original), decodeCallLog(encodeCallLog(listOf(original))))
    }

    @Test
    fun `direction and state map to wire words`() {
        val json = encodeCallLogEntry(entry(direction = Direction.OUTGOING, state = State.MISSED))

        assertEquals("out", json.getString("dir"))
        assertEquals("missed", json.getString("state"))
        assertEquals(Direction.INCOMING, decodeCallLogEntry(JSONObject().put("dir", "in")).direction)
        assertEquals(State.ANSWERED, decodeCallLogEntry(JSONObject().put("state", "answered")).state)
    }

    @Test
    fun `decode tolerates missing fields with defaults`() {
        val decoded = decodeCallLogEntry(JSONObject())

        assertEquals(CallLogEntry("", "", "", false, Direction.INCOMING, State.ANSWERED, 0L, 0L, false), decoded)
    }

    @Test
    fun `malformed json decodes to null`() {
        assertNull(decodeCallLog("{not json"))
        assertNull(decodeCallLog(""))
    }

    @Test
    fun `decode sorts by startedAt descending and skips null elements`() {
        val json = """[{"id":"old","peer":"u1","at":10},null,{"id":"new","peer":"u1","at":20}]"""

        assertEquals(listOf("new", "old"), decodeCallLog(json)?.map { it.id })
    }

    @Test
    fun `decode trims peer whitespace`() {
        val decoded = decodeCallLogEntry(JSONObject().put("peer", "  u1  "))

        assertEquals("u1", decoded.peerId)
    }

    @Test
    fun `upsert appends new entry and sorts descending`() {
        val next = upsertCallLog(listOf(entry("c1", startedAt = 10)), entry("c2", startedAt = 20))

        assertEquals(listOf("c2", "c1"), next.map { it.id })
    }

    @Test
    fun `upsert replaces same id and keeps count`() {
        val existing = listOf(entry("c1", startedAt = 10), entry("c2", startedAt = 20))
        val next = upsertCallLog(existing, entry("c1", startedAt = 10, durationMs = 9000L))

        assertEquals(2, next.size)
        assertEquals(9000L, next.first { it.id == "c1" }.durationMs)
    }

    @Test
    fun `upsert trims to max keeping newest even when late write is old`() {
        val existing = (0 until 200).map { entry("c$it", startedAt = it.toLong()) }
        val next = upsertCallLog(existing, entry("late-old", startedAt = 0L))

        assertEquals(200, next.size)
        assertTrue(next.none { it.id == "late-old" })
        assertTrue(next.any { it.id == "c0" })
        assertEquals(0L, next.last().startedAt)
    }

    @Test
    fun `upsert respects maxEntries parameter`() {
        val next = upsertCallLog(
            listOf(entry("c1", startedAt = 10), entry("c2", startedAt = 20)),
            entry("c3", startedAt = 30),
            maxEntries = 2,
        )

        assertEquals(listOf("c3", "c2"), next.map { it.id })
    }

    @Test
    fun `remove drops matching id only`() {
        val existing = listOf(entry("c1"), entry("c2"))

        assertEquals(listOf("c2"), removeCallLogEntry(existing, "c1").map { it.id })
        assertEquals(existing, removeCallLogEntry(existing, "missing"))
    }
}
