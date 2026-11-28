package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CallSessionModelsFuzzTest {

    private val random = Random(0xCA11_2026L)

    private fun randomSnapshot(): Snapshot = Snapshot(
        epoch = when (random.nextInt(5)) {
            0 -> 0L
            1 -> Long.MAX_VALUE
            2 -> Long.MIN_VALUE
            3 -> -1L
            else -> random.nextLong(0, 1_000_000)
        },
        phase = Phase.entries.random(random),
        peerId = listOf("", "u1", "u-9", "peer").random(random),
        incoming = random.nextBoolean(),
    )

    @Test
    fun `snapshot defaults are pinned`() {
        assertEquals(Snapshot(0L, Phase.IDLE, "", false), Snapshot())
        assertEquals(0L, Snapshot().epoch)
        assertEquals(Phase.IDLE, Snapshot().phase)
        assertEquals("", Snapshot().peerId)
        assertFalse(Snapshot().incoming)
    }

    @Test
    fun `snapshot copy and equality are field-wise over random values`() {
        repeat(200) {
            val snapshot = randomSnapshot()
            assertEquals(snapshot, snapshot.copy())
            assertEquals(snapshot.hashCode(), snapshot.copy().hashCode())
            assertNotEquals(snapshot, snapshot.copy(epoch = snapshot.epoch + 1))
            assertNotEquals(snapshot, snapshot.copy(phase = Phase.entries.first { it != snapshot.phase }))
            assertNotEquals(snapshot, snapshot.copy(peerId = snapshot.peerId + "-x"))
            assertNotEquals(snapshot, snapshot.copy(incoming = !snapshot.incoming))
            assertEquals(snapshot.epoch + 1, snapshot.copy(epoch = snapshot.epoch + 1).epoch)
            assertTrue(snapshot.copy(phase = Phase.CONNECTED).phase == Phase.CONNECTED)
        }
    }

    @Test
    fun `phase names and ordinals round-trip`() {
        val pinnedOrder = listOf(
            Phase.IDLE,
            Phase.OUTGOING_RINGING,
            Phase.INCOMING_RINGING,
            Phase.CONNECTING,
            Phase.CONNECTED,
            Phase.ENDING,
            Phase.ENDED,
        )
        assertEquals(pinnedOrder, Phase.entries.toList())
        Phase.entries.forEachIndexed { index, phase ->
            assertEquals(index, phase.ordinal)
            assertEquals(phase, Phase.valueOf(phase.name))
        }
        repeat(100) {
            val badName = (1..random.nextInt(1, 10)).map {
                ('a'..'z').random(random)
            }.joinToString("").uppercase()
            if (Phase.entries.none { it.name == badName }) {
                assertFailsWith<IllegalArgumentException> { Phase.valueOf(badName) }
            }
        }
    }
}
