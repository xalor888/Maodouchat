// Fuzz / random-oracle tests for SignalPreKeyIdPolicy
// (checklist line 931, 160th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: ids are valid
// in 1..16_777_215 (the 24-bit Signal range); a signed pre-key id is
// 1 + f(MAX_ID); a batch starts at maxExistingId + 1 while that fits
// in 1..maxStart, otherwise it wraps to 1 + f(maxStart), where
// maxStart = (MAX_ID - count + 1) floored at 1 and count is floored
// at 1. The oracle hardcodes 16_777_215, the value the production
// const holds. Int overflow is real here: maxExistingId + 1 wraps
// (Int.MAX_VALUE + 1 is Int.MIN_VALUE), so the oracle does the same
// addition in Kotlin and the Python model wraps it explicitly.
package com.maodouchat.core.crypto

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

internal const val PREKEY_MAX_ID160: Int = 16777215

internal fun prekeyIsValidOracle160(id: Int): Boolean = id in 1..PREKEY_MAX_ID160

internal fun prekeyRandomOracle160(randomIntExclusive: (Int) -> Int): Int =
    1 + randomIntExclusive(PREKEY_MAX_ID160)

internal fun prekeyNextBatchOracle160(
    maxExistingId: Int,
    count: Int,
    randomIntExclusive: (Int) -> Int,
): Int {
    val n = count.coerceAtLeast(1)
    val maxStart = (PREKEY_MAX_ID160 - n + 1).coerceAtLeast(1)
    val sequential = maxExistingId + 1
    return if (sequential in 1..maxStart) {
        sequential
    } else {
        1 + randomIntExclusive(maxStart)
    }
}

// ---------- generators ----------

internal val PREKEY_MODES160 = listOf("zero", "max", "half")

internal fun prekeyLambda160(mode: String): (Int) -> Int = when (mode) {
    "max" -> { n: Int -> n - 1 }
    "half" -> { n: Int -> n / 2 }
    else -> { _: Int -> 0 }
}

internal val PREKEY_ID_POOL160 = listOf(
    Int.MIN_VALUE, Int.MIN_VALUE + 1, Int.MIN_VALUE + 2, -16777215, -100, -2, -1,
    0, 1, 2, 3, 100, 101, 8388607, 8388608, 16777213, 16777214, 16777215,
    16777216, 16777217, 33554432, Int.MAX_VALUE - 2, Int.MAX_VALUE - 1,
    Int.MAX_VALUE,
)

internal val PREKEY_COUNT_POOL160 = listOf(
    Int.MIN_VALUE, -100, -2, -1, 0, 1, 2, 3, 50, 16777214, 16777215,
    16777216, Int.MAX_VALUE - 1, Int.MAX_VALUE,
)

internal fun randomPrekeyId160(rnd: Random): Int = when (rnd.nextInt(10)) {
    0 -> PREKEY_ID_POOL160[rnd.nextInt(PREKEY_ID_POOL160.size)]
    1 -> rnd.nextInt(-100, 101)
    2 -> rnd.nextInt(16777210, 16777221)
    3 -> 1 shl rnd.nextInt(0, 31)
    else -> rnd.nextInt()
}

internal fun randomPrekeyCount160(rnd: Random): Int = when (rnd.nextInt(10)) {
    0 -> PREKEY_COUNT_POOL160[rnd.nextInt(PREKEY_COUNT_POOL160.size)]
    1 -> rnd.nextInt(-100, 101)
    2 -> rnd.nextInt(1, 101)
    else -> rnd.nextInt()
}

internal data class PrekeyValidCase160(val id: Int)

internal data class PrekeyRandomCase160(val mode: String)

internal data class PrekeyNextBatchCase160(
    val maxExistingId: Int,
    val count: Int,
    val mode: String,
)

internal fun prekeyIsValidWireJson160(case: PrekeyValidCase160): String =
    "{\"id\":" + case.id + "}"

internal fun prekeyRandomWireJson160(case: PrekeyRandomCase160): String =
    "{\"mode\":\"" + case.mode + "\"}"

internal fun prekeyNextBatchWireJson160(case: PrekeyNextBatchCase160): String =
    "{\"maxExistingId\":" + case.maxExistingId + ",\"count\":" + case.count +
        ",\"mode\":\"" + case.mode + "\"}"

class SignalPreKeyIdPolicyFuzzTest {

    private fun checkValid(id: Int) {
        assertEquals(
            prekeyIsValidOracle160(id),
            SignalPreKeyIdPolicy.isValid(id),
        )
    }

    private fun checkRandom(mode: String) {
        assertEquals(
            prekeyRandomOracle160(prekeyLambda160(mode)),
            SignalPreKeyIdPolicy.randomSignedPreKeyId(prekeyLambda160(mode)),
        )
    }

    private fun checkNextBatch(maxExistingId: Int, count: Int, mode: String) {
        assertEquals(
            prekeyNextBatchOracle160(maxExistingId, count, prekeyLambda160(mode)),
            SignalPreKeyIdPolicy.nextBatchStartId(maxExistingId, count, prekeyLambda160(mode)),
        )
    }

    @Test
    fun pinnedBoundaries() {
        // isValid: the 24-bit window, closed on both ends.
        assertEquals(true, SignalPreKeyIdPolicy.isValid(1))
        assertEquals(true, SignalPreKeyIdPolicy.isValid(16777215))
        assertEquals(true, SignalPreKeyIdPolicy.isValid(8388608))
        assertEquals(false, SignalPreKeyIdPolicy.isValid(0))
        assertEquals(false, SignalPreKeyIdPolicy.isValid(16777216))
        assertEquals(false, SignalPreKeyIdPolicy.isValid(-1))
        assertEquals(false, SignalPreKeyIdPolicy.isValid(Int.MIN_VALUE))
        assertEquals(false, SignalPreKeyIdPolicy.isValid(Int.MAX_VALUE))
        assertEquals(true, prekeyIsValidOracle160(16777215))
        assertEquals(false, prekeyIsValidOracle160(16777216))
        // randomSignedPreKeyId: 1 + f(MAX_ID).
        assertEquals(1, SignalPreKeyIdPolicy.randomSignedPreKeyId { 0 })
        assertEquals(16777215, SignalPreKeyIdPolicy.randomSignedPreKeyId { it - 1 })
        assertEquals(8388608, SignalPreKeyIdPolicy.randomSignedPreKeyId { it / 2 })
        // nextBatchStartId: sequential fits, the lambda is never called.
        assertEquals(
            101,
            SignalPreKeyIdPolicy.nextBatchStartId(100, 50) { error("random must not be used when sequential fits") },
        )
        assertEquals(1, SignalPreKeyIdPolicy.nextBatchStartId(0, 1) { 0 })
        assertEquals(16777215, SignalPreKeyIdPolicy.nextBatchStartId(16777214, 1) { 0 })
        assertEquals(1, SignalPreKeyIdPolicy.nextBatchStartId(16777214, 2) { 0 })
        // Sequential just past the end wraps to the random branch.
        assertEquals(1, SignalPreKeyIdPolicy.nextBatchStartId(16777215, 1) { 0 })
        assertEquals(16777166, SignalPreKeyIdPolicy.nextBatchStartId(16777205, 50) { it - 1 })
        assertEquals(1, SignalPreKeyIdPolicy.nextBatchStartId(16777205, 50) { 0 })
        // Int overflow: maxExistingId + 1 wraps to Int.MIN_VALUE, out of range.
        assertEquals(1, SignalPreKeyIdPolicy.nextBatchStartId(Int.MAX_VALUE, 1) { 0 })
        assertEquals(1, SignalPreKeyIdPolicy.nextBatchStartId(Int.MIN_VALUE, 1) { 0 })
        // Negative / zero counts are floored to 1.
        assertEquals(101, SignalPreKeyIdPolicy.nextBatchStartId(100, 0) { 0 })
        assertEquals(101, SignalPreKeyIdPolicy.nextBatchStartId(100, -7) { 0 })
        assertEquals(1, SignalPreKeyIdPolicy.nextBatchStartId(100, Int.MAX_VALUE) { 0 })
        assertEquals(1, SignalPreKeyIdPolicy.nextBatchStartId(-5, 10) { 0 })
        assertEquals(1, SignalPreKeyIdPolicy.nextBatchStartId(-1, 1) { 0 })
        for (id in PREKEY_ID_POOL160) {
            checkValid(id)
        }
        for (mode in PREKEY_MODES160) {
            checkRandom(mode)
        }
        for (count in PREKEY_COUNT_POOL160) {
            checkNextBatch(100, count, "zero")
            checkNextBatch(16777205, count, "max")
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(160003)
        repeat(2000) {
            when (rnd.nextInt(3)) {
                0 -> checkValid(randomPrekeyId160(rnd))
                1 -> checkRandom(PREKEY_MODES160[rnd.nextInt(PREKEY_MODES160.size)])
                else -> checkNextBatch(
                    randomPrekeyId160(rnd),
                    randomPrekeyCount160(rnd),
                    PREKEY_MODES160[rnd.nextInt(PREKEY_MODES160.size)],
                )
            }
        }
    }

    @Test
    fun sweepExhaustive() {
        // isValid over the boundary set, powers of two, and neighbours.
        for (id in PREKEY_ID_POOL160) {
            checkValid(id)
        }
        for (k in 0..30) {
            val p = 1 shl k
            checkValid(p - 1)
            checkValid(p)
            checkValid(p + 1)
        }
        for (id in -10..10) {
            checkValid(id)
        }
        for (id in 16777205..16777225) {
            checkValid(id)
        }
        // randomSignedPreKeyId over every lambda mode.
        for (mode in PREKEY_MODES160) {
            checkRandom(mode)
        }
        // nextBatchStartId over the boundary grid, every mode.
        val maxIds = listOf(
            Int.MIN_VALUE, Int.MIN_VALUE + 1, -2, -1, 0, 1, 2, 99, 100, 101,
            16777213, 16777214, 16777215, 16777216, Int.MAX_VALUE - 1, Int.MAX_VALUE,
        )
        val counts = listOf(
            Int.MIN_VALUE, -1, 0, 1, 2, 3, 50, 16777214, 16777215, 16777216,
            Int.MAX_VALUE,
        )
        for (maxExistingId in maxIds) {
            for (count in counts) {
                for (mode in PREKEY_MODES160) {
                    checkNextBatch(maxExistingId, count, mode)
                }
            }
        }
    }
}
