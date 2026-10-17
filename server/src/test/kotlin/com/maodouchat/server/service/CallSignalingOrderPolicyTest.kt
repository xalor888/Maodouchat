package com.maodouchat.server.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CallSignalingOrderPolicyTest {

    @Test
    fun legacyUnsetAlwaysAccepted() {
        val decision = CallSignalingOrderPolicy.admit(
            Cursor(0, 0),
            lastAccepted = Cursor(5, 9),
        )
        assertIs<Accept>(decision)
    }

    @Test
    fun firstSignalAccepted() {
        assertIs<Accept>(
            CallSignalingOrderPolicy.admit(Cursor(1, 1), null)
        )
    }

    @Test
    fun higherSequenceAccepted() {
        assertIs<Accept>(
            CallSignalingOrderPolicy.admit(
                Cursor(1, 3),
                lastAccepted = Cursor(1, 2),
            )
        )
    }

    @Test
    fun equalCursorAcceptedForIdempotentRetry() {
        assertIs<Accept>(
            CallSignalingOrderPolicy.admit(
                Cursor(1, 2),
                lastAccepted = Cursor(1, 2),
            )
        )
    }

    @Test
    fun lowerSequenceRejected() {
        assertIs<RejectStale>(
            CallSignalingOrderPolicy.admit(
                Cursor(1, 1),
                lastAccepted = Cursor(1, 2),
            )
        )
    }

    @Test
    fun lowerEpochRejectedEvenWithHigherSequence() {
        assertIs<RejectStale>(
            CallSignalingOrderPolicy.admit(
                Cursor(1, 99),
                lastAccepted = Cursor(2, 0),
            )
        )
    }

    @Test
    fun higherEpochAccepted() {
        assertIs<Accept>(
            CallSignalingOrderPolicy.admit(
                Cursor(2, 0),
                lastAccepted = Cursor(1, 50),
            )
        )
    }

    @Test
    fun deliveryOrderPrefersEpochThenSequenceThenTimestamp() {
        val ordered = listOf(
            Quad(1L, 2L, 100L, "b"),
            Quad(2L, 0L, 50L, "a"),
            Quad(1L, 1L, 200L, "c"),
            Quad(1L, 2L, 90L, "a"),
        ).sortedWith { x, y ->
            CallSignalingOrderPolicy.compareForDelivery(
                x.epoch, x.sequence, x.ts, x.id,
                y.epoch, y.sequence, y.ts, y.id,
            )
        }
        assertEquals(
            listOf("c", "a", "b", "a"),
            ordered.map { it.id },
        )
        assertEquals(listOf(1L to 1L, 1L to 2L, 1L to 2L, 2L to 0L), ordered.map { it.epoch to it.sequence })
    }

    private data class Quad(val epoch: Long, val sequence: Long, val ts: Long, val id: String)
}
