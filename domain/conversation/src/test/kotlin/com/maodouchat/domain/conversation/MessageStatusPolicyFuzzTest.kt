package com.maodouchat.domain.conversation

import com.maodouchat.core.model.MessageStatus
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// deliveryRank reimplemented as a table (SENDING 0 / SENT 1 /
// DELIVERED 2 / READ 3 / FAILED -1); canAdvanceTo reimplemented
// from the production branches: same state advances, FAILED only
// replaces SENDING, a FAILED state is only overwritten by a real
// delivery state, otherwise the rank must strictly increase.
internal fun deliveryRankOracle165(status: MessageStatus): Int = when (status) {
    MessageStatus.SENDING -> 0
    MessageStatus.SENT -> 1
    MessageStatus.DELIVERED -> 2
    MessageStatus.READ -> 3
    MessageStatus.FAILED -> -1
}

internal fun canAdvanceOracle165(from: MessageStatus, to: MessageStatus): Boolean {
    if (from == to) return true
    if (to == MessageStatus.FAILED) return from == MessageStatus.SENDING
    if (from == MessageStatus.FAILED) {
        return to == MessageStatus.SENDING || to == MessageStatus.SENT ||
            to == MessageStatus.DELIVERED || to == MessageStatus.READ
    }
    return deliveryRankOracle165(to) > deliveryRankOracle165(from)
}

// ---------- generators ----------

internal data class AdvanceCase165(val from: Int, val to: Int)

internal fun randomAdvanceCase165(rnd: Random): AdvanceCase165 =
    AdvanceCase165(rnd.nextInt(5), rnd.nextInt(5))

internal fun advWireJson165(c: AdvanceCase165): String =
    "{\"a\":${c.from},\"b\":${c.to}}"

class MessageStatusPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val s = MessageStatus.SENDING
        val se = MessageStatus.SENT
        val d = MessageStatus.DELIVERED
        val r = MessageStatus.READ
        val f = MessageStatus.FAILED
        val cases = listOf(
            Triple(s, s, true), Triple(s, se, true), Triple(s, d, true),
            Triple(s, r, true), Triple(s, f, true),
            Triple(se, s, false), Triple(se, se, true), Triple(se, d, true),
            Triple(se, r, true), Triple(se, f, false),
            Triple(d, s, false), Triple(d, se, false), Triple(d, d, true),
            Triple(d, r, true), Triple(d, f, false),
            Triple(r, s, false), Triple(r, se, false), Triple(r, d, false),
            Triple(r, r, true), Triple(r, f, false),
            Triple(f, s, true), Triple(f, se, true), Triple(f, d, true),
            Triple(f, r, true), Triple(f, f, true),
        )
        for ((from, to, expected) in cases) {
            assertEquals(expected, from.canAdvanceTo(to), "canAdvanceTo($from,$to)")
            assertEquals(expected, canAdvanceOracle165(from, to), "oracle($from,$to)")
        }
        val ranks = mapOf(s to 0, se to 1, d to 2, r to 3, f to -1)
        for ((status, rank) in ranks) {
            assertEquals(rank, status.deliveryRank(), "deliveryRank($status)")
            assertEquals(rank, deliveryRankOracle165(status), "oracleRank($status)")
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(165003)
        repeat(4000) {
            val c = randomAdvanceCase165(rnd)
            val from = MessageStatus.entries[c.from]
            val to = MessageStatus.entries[c.to]
            assertEquals(
                canAdvanceOracle165(from, to),
                from.canAdvanceTo(to),
                "canAdvanceTo($from,$to)"
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        for (from in MessageStatus.entries) {
            for (to in MessageStatus.entries) {
                assertEquals(
                    canAdvanceOracle165(from, to),
                    from.canAdvanceTo(to),
                    "canAdvanceTo($from,$to)"
                )
            }
        }
    }
}
