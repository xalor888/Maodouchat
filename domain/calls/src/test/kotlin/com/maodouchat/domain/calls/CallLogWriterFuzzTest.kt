package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallLogWriterFuzzTest {

    private val random = Random(0xCA11_2026L)

    private val callIdPool = listOf("call-1", "call-2", "", "  ", "call-9")
    private val peerPool = listOf("u2", "u3", "", "  ", "u9")
    private val namePool = listOf("Bob", "", "  ", "Alice", "名字")
    private val ownerPool = listOf("u1", "", "  ", "u9")

    private class Fixture {
        var now = 5_000L
        var callId = "call-1"
        var ownerId = "u1"
        var state = CallUiState(contactId = "u2", contactName = "Bob")
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

    private fun randomMillis(): Long = random.nextLong(-1_000_000, 10_000_000)

    private fun randomState(): CallUiState = CallUiState(
        contactId = peerPool.random(random),
        contactName = namePool.random(random),
        callType = CallType.entries.random(random),
        isIncoming = random.nextBoolean(),
        isGroupCall = random.nextBoolean(),
    )

    private fun randomStoredEntry(): CallLogEntry = CallLogEntry(
        id = callIdPool.random(random),
        peerId = peerPool.random(random),
        peerName = namePool.random(random),
        isVideo = random.nextBoolean(),
        direction = Direction.entries.random(random),
        state = State.entries.random(random),
        startedAt = randomMillis(),
        durationMs = random.nextLong(0, 600_000),
        isGroup = random.nextBoolean(),
    )

    private fun randomFixture(): Fixture {
        val f = Fixture()
        f.now = randomMillis()
        f.callId = callIdPool.random(random)
        f.ownerId = ownerPool.random(random)
        f.state = randomState()
        repeat(random.nextInt(0, 4)) { f.stored += randomStoredEntry() }
        return f
    }

    // writeCallLog 的逐字复述：callId/peerId 为空不写；startedAt 在
    // ANSWERED 盖 MISSED 时重置为 now、否则沿用已有、否则 now；duration 只在
    // ANSWERED 盖 ANSWERED 时累加（now - startedAt，下限 0），否则沿用已有或 0。
    private fun oracleWrite(f: Fixture, state: State): Pair<CallLogEntry, String?>? {
        val st = f.state
        val callId = f.callId
        val peerId = st.contactId
        if (callId.isBlank() || peerId.isBlank()) return null
        val existing = f.stored.filter { it.peerId == peerId }.firstOrNull { it.id == callId }
        val startedAt = when {
            state == State.ANSWERED && existing?.state == State.MISSED -> f.now
            existing != null -> existing.startedAt
            else -> f.now
        }
        val durationMs = if (state == State.ANSWERED && existing?.state == State.ANSWERED) {
            (f.now - startedAt).coerceAtLeast(0L)
        } else {
            existing?.durationMs ?: 0L
        }
        return CallLogEntry(
            id = callId,
            peerId = peerId,
            peerName = st.contactName.ifBlank { peerId },
            isVideo = st.callType == CallType.VIDEO,
            direction = if (st.isIncoming) Direction.INCOMING else Direction.OUTGOING,
            state = state,
            startedAt = startedAt,
            durationMs = durationMs,
            isGroup = st.isGroupCall,
        ) to f.ownerId.takeIf { it.isNotBlank() }
    }

    @Test
    fun `write matches oracle over random states`() {
        repeat(300) {
            val f = randomFixture()
            val state = State.entries.random(random)
            f.writer().writeCallLog(state)
            assertEquals(oracleWrite(f, state), f.upserts.singleOrNull(), "state=$state fixture now=${f.now}")
        }
    }

    @Test
    fun `blank call id or peer id never writes`() {
        repeat(100) {
            val f = randomFixture()
            f.callId = listOf("", "   ").random(random)
            f.state = f.state.copy(contactId = listOf("", "   ").random(random))
            f.writer().writeCallLog(State.entries.random(random))
            assertTrue(f.upserts.isEmpty())
        }
    }

    @Test
    fun `repeated writes thread through the stored entry`() {
        repeat(100) {
            val f = randomFixture()
            f.callId = "call-1"
            f.state = f.state.copy(contactId = "u2")
            f.stored.removeAll { it.id == "call-1" && it.peerId == "u2" }
            repeat(5) {
                val state = State.entries.random(random)
                f.now = randomMillis()
                f.writer().writeCallLog(state)
                val expected = oracleWrite(f, state)
                assertEquals(expected, f.upserts.lastOrNull())
                // 模拟存储回写：下一次写读到的是本次 upsert 的条目。
                val entry = expected?.first ?: return@repeat
                f.stored.removeAll { it.id == entry.id && it.peerId == entry.peerId }
                f.stored += entry
            }
        }
    }

    @Test
    fun `duration never goes negative when the clock runs backwards`() {
        repeat(100) {
            val f = Fixture()
            f.now = 1_000L
            f.stored += CallLogEntry(
                id = "call-1", peerId = "u2", peerName = "Bob", isVideo = true,
                direction = Direction.INCOMING, state = State.ANSWERED,
                startedAt = 9_000L, durationMs = 100L, isGroup = false,
            )
            f.writer().writeCallLog(State.ANSWERED)
            val entry = f.upserts.single().first
            assertEquals(9_000L, entry.startedAt)
            assertEquals(0L, entry.durationMs)
        }
    }

    @Test
    fun `owner guard passes null only for blank owner`() {
        repeat(100) {
            val f = randomFixture()
            f.callId = "call-1"
            f.state = f.state.copy(contactId = "u2")
            f.writer().writeCallLog(State.MISSED)
            assertEquals(
                f.ownerId.takeIf { it.isNotBlank() },
                f.upserts.single().second,
                "owner=${f.ownerId}",
            )
        }
    }
}
