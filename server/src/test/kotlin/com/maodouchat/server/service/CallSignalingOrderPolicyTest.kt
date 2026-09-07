package com.maodouchat.server.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CallSignalingOrderPolicyTest {

    @Test
    fun legacyUnsetAlwaysAccepted() {
        val decision = CallSignalingOrderPolicy.admit(
            CallSignalingOrderPolicy.Cursor(0, 0),
            lastAccepted = CallSignalingOrderPolicy.Cursor(5, 9),
        )
        assertIs<CallSignalingOrderPolicy.Admit.Accept>(decision)
    }

    @Test
    fun firstSignalAccepted() {
        assertIs<CallSignalingOrderPolicy.Admit.Accept>(
            CallSignalingOrderPolicy.admit(CallSignalingOrderPolicy.Cursor(1, 1), null)
        )
    }

    @Test
    fun higherSequenceAccepted() {
        assertIs<CallSignalingOrderPolicy.Admit.Accept>(
            CallSignalingOrderPolicy.admit(
                CallSignalingOrderPolicy.Cursor(1, 3),
                lastAccepted = CallSignalingOrderPolicy.Cursor(1, 2),
            )
        )
    }

    @Test
    fun equalCursorAcceptedForIdempotentRetry() {
        assertIs<CallSignalingOrderPolicy.Admit.Accept>(
            CallSignalingOrderPolicy.admit(
                CallSignalingOrderPolicy.Cursor(1, 2),
                lastAccepted = CallSignalingOrderPolicy.Cursor(1, 2),
            )
        )
    }

    @Test
    fun lowerSequenceRejected() {
        assertIs<CallSignalingOrderPolicy.Admit.RejectStale>(
            CallSignalingOrderPolicy.admit(
                CallSignalingOrderPolicy.Cursor(1, 1),
                lastAccepted = CallSignalingOrderPolicy.Cursor(1, 2),
            )
        )
    }

    @Test
    fun lowerEpochRejectedEvenWithHigherSequence() {
        assertIs<CallSignalingOrderPolicy.Admit.RejectStale>(
            CallSignalingOrderPolicy.admit(
                CallSignalingOrderPolicy.Cursor(1, 99),
                lastAccepted = CallSignalingOrderPolicy.Cursor(2, 0),
            )
        )
    }

    @Test
    fun higherEpochAccepted() {
        assertIs<CallSignalingOrderPolicy.Admit.Accept>(
            CallSignalingOrderPolicy.admit(
                CallSignalingOrderPolicy.Cursor(2, 0),
                lastAccepted = CallSignalingOrderPolicy.Cursor(1, 50),
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
