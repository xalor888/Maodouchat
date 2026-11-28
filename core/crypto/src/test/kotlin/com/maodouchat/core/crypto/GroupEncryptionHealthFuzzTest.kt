package com.maodouchat.core.crypto

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// GroupEncryptionHealthPolicy.evaluate reimplemented from the spec:
// unknown current epoch wins over everything, then missing key or
// unknown local epoch, then a local epoch behind current, else healthy.
internal fun healthOracle164(
    hasDistributionKey: Boolean,
    localEpoch: Long?,
    currentEpoch: Long?,
): GroupEncryptionHealth = when {
    currentEpoch == null || currentEpoch <= 0L -> GroupEncryptionHealth.UNKNOWN_EPOCH
    !hasDistributionKey || localEpoch == null -> GroupEncryptionHealth.MISSING_KEY
    localEpoch < currentEpoch -> GroupEncryptionHealth.STALE_EPOCH
    else -> GroupEncryptionHealth.HEALTHY
}

// ---------- generators ----------

internal val HEALTH_EPOCH_POOL164: List<Long> = listOf(
    Long.MIN_VALUE, Long.MIN_VALUE + 1, -1000L, -2L, -1L, 0L, 1L, 2L, 3L, 4L, 5L,
    6L, 10L, 100L, 1000L, Long.MAX_VALUE - 1, Long.MAX_VALUE,
)

internal fun randomHealthEpoch164(rnd: Random): Long? {
    return when (rnd.nextInt(10)) {
        0 -> null
        1 -> HEALTH_EPOCH_POOL164[rnd.nextInt(HEALTH_EPOCH_POOL164.size)]
        2 -> rnd.nextLong(-10L, 11L)
        3 -> rnd.nextLong(0L, 100L)
        else -> rnd.nextLong()
    }
}

internal data class HealthCase164(
    val hasKey: Boolean,
    val local: Long?,
    val current: Long?,
)

internal fun randomHealthCase164(rnd: Random): HealthCase164 =
    HealthCase164(rnd.nextBoolean(), randomHealthEpoch164(rnd), randomHealthEpoch164(rnd))

internal fun healthWireJson164(case: HealthCase164): String {
    val local = case.local?.toString() ?: "null"
    val current = case.current?.toString() ?: "null"
    return "{\"hasKey\":${case.hasKey},\"local\":$local,\"current\":$current}"
}

class GroupEncryptionHealthFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val cases = listOf(
            Triple(true, 5L, 5L) to GroupEncryptionHealth.HEALTHY,
            Triple(true, 6L, 5L) to GroupEncryptionHealth.HEALTHY,
            Triple(true, Long.MAX_VALUE, Long.MAX_VALUE) to GroupEncryptionHealth.HEALTHY,
            Triple(true, Long.MAX_VALUE, 1L) to GroupEncryptionHealth.HEALTHY,
            Triple(true, 3L, 5L) to GroupEncryptionHealth.STALE_EPOCH,
            Triple(true, 0L, 1L) to GroupEncryptionHealth.STALE_EPOCH,
            Triple(true, -1L, 1L) to GroupEncryptionHealth.STALE_EPOCH,
            Triple(true, Long.MIN_VALUE, Long.MAX_VALUE) to GroupEncryptionHealth.STALE_EPOCH,
            Triple(true, 4L, 5L) to GroupEncryptionHealth.STALE_EPOCH,
            Triple(false, 5L, 5L) to GroupEncryptionHealth.MISSING_KEY,
            Triple(false, null, 5L) to GroupEncryptionHealth.MISSING_KEY,
            Triple(true, null, 5L) to GroupEncryptionHealth.MISSING_KEY,
            Triple(false, 3L, 5L) to GroupEncryptionHealth.MISSING_KEY,
            Triple(true, 5L, null) to GroupEncryptionHealth.UNKNOWN_EPOCH,
            Triple(true, null, null) to GroupEncryptionHealth.UNKNOWN_EPOCH,
            Triple(false, null, null) to GroupEncryptionHealth.UNKNOWN_EPOCH,
            Triple(true, 5L, 0L) to GroupEncryptionHealth.UNKNOWN_EPOCH,
            Triple(true, 5L, -1L) to GroupEncryptionHealth.UNKNOWN_EPOCH,
            Triple(true, 5L, Long.MIN_VALUE) to GroupEncryptionHealth.UNKNOWN_EPOCH,
            Triple(false, 5L, 0L) to GroupEncryptionHealth.UNKNOWN_EPOCH,
            Triple(false, null, -5L) to GroupEncryptionHealth.UNKNOWN_EPOCH,
            Triple(true, 0L, 0L) to GroupEncryptionHealth.UNKNOWN_EPOCH,
            Triple(true, -1L, -1L) to GroupEncryptionHealth.UNKNOWN_EPOCH,
            Triple(true, 1L, 1L) to GroupEncryptionHealth.HEALTHY,
            Triple(true, 0L, Long.MAX_VALUE) to GroupEncryptionHealth.STALE_EPOCH,
        )
        for ((input, expected) in cases) {
            val (hasKey, local, current) = input
            val actual = GroupEncryptionHealthPolicy.evaluate(hasKey, local, current)
            assertEquals(expected, actual, "evaluate($hasKey,$local,$current)")
            assertEquals(expected, healthOracle164(hasKey, local, current), "oracle($hasKey,$local,$current)")
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(164003)
        repeat(4000) {
            val case = randomHealthCase164(rnd)
            assertEquals(
                healthOracle164(case.hasKey, case.local, case.current),
                GroupEncryptionHealthPolicy.evaluate(case.hasKey, case.local, case.current),
                "evaluate($case)",
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        val locals: List<Long?> = listOf(null) + HEALTH_EPOCH_POOL164
        val currents: List<Long?> = listOf(null) + HEALTH_EPOCH_POOL164
        for (hasKey in listOf(false, true)) {
            for (local in locals) {
                for (current in currents) {
                    assertEquals(
                        healthOracle164(hasKey, local, current),
                        GroupEncryptionHealthPolicy.evaluate(hasKey, local, current),
                        "evaluate($hasKey,$local,$current)",
                    )
                }
            }
        }
    }
}
