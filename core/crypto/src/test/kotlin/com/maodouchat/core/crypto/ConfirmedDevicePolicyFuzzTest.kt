package com.maodouchat.core.crypto

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// Valid device ids are 1..255; the confirmed list is filtered, deduped
// and sorted before the smallest is picked. A request equal to the
// default slot takes the default path even when out of range.
internal fun resolveDeviceOracle163(requested: Int, confirmed: List<Int>, default: Int): Int? {
    val clean = confirmed.filter { it in 1..255 }.distinct().sorted()
    if (requested != default) return requested.takeIf { it in 1..255 }
    if (requested in clean) return requested
    return clean.minOrNull()
}

// ---------- generators ----------

internal val DEV_INT_POOL163 = listOf(
    Int.MIN_VALUE, -1000, -256, -5, -1, 0, 1, 2, 3, 42, 127, 254, 255, 256, 257,
    1000, Int.MAX_VALUE,
)

internal val DEV_DEF_POOL163 = listOf(
    Int.MIN_VALUE, -3, -1, 0, 1, 2, 5, 9, 255, 256, 300, Int.MAX_VALUE,
)

internal fun randomDevInt163(rnd: Random): Int = when (rnd.nextInt(10)) {
    0 -> rnd.nextInt()
    1 -> rnd.nextInt(-5, 261)
    else -> DEV_INT_POOL163[rnd.nextInt(DEV_INT_POOL163.size)]
}

internal fun randomDevList163(rnd: Random): List<Int> {
    val n = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> 1
        2 -> rnd.nextInt(2, 6)
        else -> rnd.nextInt(0, 4)
    }
    return List(n) { randomDevInt163(rnd) }
}

internal data class DevCase163(
    val requested: Int,
    val confirmed: List<Int>,
    val default: Int,
)

internal fun randomDevCase163(rnd: Random): DevCase163 {
    val def = when (rnd.nextInt(10)) {
        0 -> 1
        1 -> DEV_DEF_POOL163[rnd.nextInt(DEV_DEF_POOL163.size)]
        else -> randomDevInt163(rnd)
    }
    val requested = when (rnd.nextInt(10)) {
        0 -> def
        1 -> DEV_INT_POOL163[rnd.nextInt(DEV_INT_POOL163.size)]
        else -> randomDevInt163(rnd)
    }
    return DevCase163(requested, randomDevList163(rnd), def)
}

internal fun devWireJson163(case: DevCase163): String {
    val sb = StringBuilder()
    sb.append("{\"req\":").append(case.requested)
    sb.append(",\"conf\":[")
    case.confirmed.forEachIndexed { index, v ->
        if (index > 0) sb.append(",")
        sb.append(v)
    }
    sb.append("],\"def\":").append(case.default)
    sb.append("}")
    return sb.toString()
}

class ConfirmedDevicePolicyFuzzTest {

    private fun check(requested: Int, confirmed: List<Int>, default: Int) {
        assertEquals(
            resolveDeviceOracle163(requested, confirmed, default),
            ConfirmedDevicePolicy.resolve(requested, confirmed, default),
        )
    }

    private fun check(case: DevCase163) = check(case.requested, case.confirmed, case.default)

    @Test
    fun pinnedBoundaries() {
        // Legacy default skips the pending ghost and picks the smallest.
        check(1, listOf(2, 5), 1)
        check(1, listOf(1, 2), 1)
        check(1, emptyList(), 1)
        check(1, listOf(9, 3, 5), 1)
        check(1, listOf(0, -4, 260, 7), 1)
        check(1, listOf(0, 999), 1)
        check(1, listOf(5, 2, 5, 2), 1)
        // Explicit in-range requests ignore the confirmed list.
        check(7, listOf(2, 5), 1)
        check(3, listOf(3), 1)
        check(9, emptyList(), 1)
        check(200, listOf(3, 4), 1)
        // Explicit out-of-range requests never fall back.
        check(0, listOf(3), 1)
        check(256, listOf(3), 1)
        check(-5, listOf(1, 2), 1)
        check(1000, listOf(1), 1)
        check(Int.MAX_VALUE, listOf(2, 4), 1)
        check(Int.MIN_VALUE, listOf(3), 1)
        // Custom default slot: equal-to-default takes the default path.
        check(2, listOf(2, 4), 2)
        check(2, listOf(4), 2)
        check(2, listOf(9, 4), 2)
        check(2, emptyList(), 2)
        check(256, listOf(3), 256)
        check(0, listOf(3), 0)
        check(-3, listOf(2, 9), -3)
        check(300, listOf(2, 9), 300)
        check(Int.MIN_VALUE, listOf(3), Int.MIN_VALUE)
        check(255, listOf(1, 9), 255)
        check(255, listOf(255), 255)
        check(9, listOf(4, 12), 9)
        check(1, listOf(2), 2)
        check(300, listOf(1, 2), 2)
        // Range edges on the confirmed side.
        check(1, listOf(255), 1)
        check(1, listOf(0, 256, 254), 1)
        check(1, listOf(Int.MIN_VALUE, -1, 0, 256, Int.MAX_VALUE, 42), 1)
        check(1, listOf(255, 200, 150), 1)
        check(1, listOf(256, 256, 1, 0, 0), 1)
        for (req in DEV_INT_POOL163) {
            check(req, listOf(1, 2, 255), 1)
            check(req, emptyList(), 1)
        }
        for (def in DEV_DEF_POOL163) {
            check(def, listOf(2, 9), def)
            check(1, listOf(2, 9), def)
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(163003)
        repeat(2000) {
            check(randomDevCase163(rnd))
        }
    }

    @Test
    fun sweepExhaustive() {
        // Requested x default over the boundary set, three list shapes.
        val lists = listOf(
            emptyList(),
            listOf(1, 2, 255),
            listOf(0, 256, -1, 42, 42),
        )
        for (req in DEV_INT_POOL163) {
            for (def in DEV_DEF_POOL163) {
                for (conf in lists) {
                    check(req, conf, def)
                }
            }
        }
        // Every single-element confirmed list over the near range.
        for (v in -2..258) {
            check(1, listOf(v), 1)
            check(2, listOf(v), 1)
            check(v, listOf(1), 1)
            check(1, listOf(v, v, v), 1)
        }
        // All pairs from a small pool, both orders.
        val pool = listOf(-1, 0, 1, 2, 3, 255, 256)
        for (a in pool) {
            for (b in pool) {
                check(1, listOf(a, b), 1)
            }
        }
        // Full confirmed range and its reverse.
        check(1, (1..255).toList(), 1)
        check(1, (255 downTo 1).toList(), 1)
        check(1, (0..300).toList(), 1)
        check(1, (1..255).toList() + (1..255).toList(), 1)
        // Requested sweep against a fixed list, both default slots.
        for (req in -10..270) {
            check(req, listOf(3, 7), 1)
            check(req, listOf(3, 7), 9)
        }
    }
}
