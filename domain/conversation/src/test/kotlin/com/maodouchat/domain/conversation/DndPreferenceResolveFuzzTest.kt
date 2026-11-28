// Fuzz / random-oracle tests for DndPreferenceResolve
// (checklist line 931, 153rd block). Test-only, zero production changes.
// The oracle is hand-written from the production source: an explicit
// switch wins, otherwise a legacy hour window counts only when start
// != end after clamping to 0..23; minutes clamp to 0..1439.
package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

internal fun coerceInOracle153(value: Int, lo: Int, hi: Int): Int =
    if (value < lo) lo else if (value > hi) hi else value

internal fun enabledOracle153(
    enabledStored: Boolean?,
    startHourPresent: Boolean,
    endHourPresent: Boolean,
    startHour: Int,
    endHour: Int,
): Boolean {
    if (enabledStored != null) return enabledStored
    if (!startHourPresent && !endHourPresent) return false
    return coerceInOracle153(startHour, 0, 23) != coerceInOracle153(endHour, 0, 23)
}

internal fun startMinuteOracle153(storedMinute: Int?, startHour: Int): Int =
    if (storedMinute != null) coerceInOracle153(storedMinute, 0, 1439)
    else coerceInOracle153(startHour, 0, 23) * 60

internal fun endMinuteOracle153(storedMinute: Int?, endHour: Int): Int =
    if (storedMinute != null) coerceInOracle153(storedMinute, 0, 1439)
    else coerceInOracle153(endHour, 0, 23) * 60

// ---------- generators ----------

internal data class DndInput153(
    val stored: Boolean?,
    val startPresent: Boolean,
    val endPresent: Boolean,
    val startHour: Int,
    val endHour: Int,
    val startMinuteStored: Int?,
    val endMinuteStored: Int?,
)

internal val HOUR_POOL153 = listOf(
    -30, -1, 0, 1, 5, 12, 22, 23, 24, 25, 30, Int.MIN_VALUE, Int.MAX_VALUE,
)

internal val MINUTE_POOL153 = listOf(
    -100, -1, 0, 1, 59, 60, 90, 720, 1439, 1440, 1600, Int.MIN_VALUE, Int.MAX_VALUE,
)

internal fun randomHour153(rnd: Random): Int = when (rnd.nextInt(10)) {
    0 -> rnd.nextInt(-30, 31)
    1 -> HOUR_POOL153[rnd.nextInt(HOUR_POOL153.size)]
    else -> rnd.nextInt(0, 24)
}

internal fun randomMinute153(rnd: Random): Int = when (rnd.nextInt(10)) {
    0 -> rnd.nextInt(-100, 1601)
    1 -> MINUTE_POOL153[rnd.nextInt(MINUTE_POOL153.size)]
    else -> rnd.nextInt(0, 1440)
}

internal fun randomDndInput153(rnd: Random): DndInput153 {
    val stored = when (rnd.nextInt(3)) {
        0 -> null
        1 -> true
        else -> false
    }
    val startMinuteStored = if (rnd.nextInt(10) < 4) null else randomMinute153(rnd)
    val endMinuteStored = if (rnd.nextInt(10) < 4) null else randomMinute153(rnd)
    return DndInput153(
        stored = stored,
        startPresent = rnd.nextBoolean(),
        endPresent = rnd.nextBoolean(),
        startHour = randomHour153(rnd),
        endHour = randomHour153(rnd),
        startMinuteStored = startMinuteStored,
        endMinuteStored = endMinuteStored,
    )
}

internal fun dndWire153(input: DndInput153): String {
    val stored = when (input.stored) {
        null -> "n"
        true -> "t"
        else -> "f"
    }
    val sm = input.startMinuteStored?.toString() ?: "n"
    val em = input.endMinuteStored?.toString() ?: "n"
    val sp = if (input.startPresent) "1" else "0"
    val ep = if (input.endPresent) "1" else "0"
    return "$stored,$sp,$ep,${input.startHour},${input.endHour},$sm,$em"
}

class DndPreferenceResolveFuzzTest {

    private fun check(input: DndInput153) {
        assertEquals(
            enabledOracle153(input.stored, input.startPresent, input.endPresent, input.startHour, input.endHour),
            DndPreferenceResolve.enabled(input.stored, input.startPresent, input.endPresent, input.startHour, input.endHour),
        )
        assertEquals(
            startMinuteOracle153(input.startMinuteStored, input.startHour),
            DndPreferenceResolve.startMinute(input.startMinuteStored, input.startHour),
        )
        assertEquals(
            endMinuteOracle153(input.endMinuteStored, input.endHour),
            DndPreferenceResolve.endMinute(input.endMinuteStored, input.endHour),
        )
    }

    @Test
    fun pinnedBoundaries() {
        // Hand-computed expectations; oracle must agree with both.
        val enabledCases = listOf(
            // stored, startPresent, endPresent, startHour, endHour -> expected
            listOf(null, false, false, 0, 0) to false,
            listOf(null, true, false, 5, 5) to false,
            listOf(null, true, false, 5, 6) to true,
            listOf(null, false, true, 0, 0) to false,
            listOf(null, true, true, 25, -3) to true,
            listOf(true, true, true, 5, 5) to true,
            listOf(false, true, true, 5, 6) to false,
            listOf(null, true, true, 23, 23) to false,
            listOf(null, true, true, 24, 24) to false,
            listOf(null, true, true, -1, 0) to false,
            listOf(null, true, true, -1, 1) to true,
            listOf(null, true, true, 0, 23) to true,
            listOf(null, false, false, 5, 6) to false,
            listOf(true, false, false, 0, 0) to true,
        )
        for ((args, expected) in enabledCases) {
            val (stored, sp, ep, sh, eh) = args
            val input = DndInput153(stored as Boolean?, sp as Boolean, ep as Boolean, sh as Int, eh as Int, null, null)
            assertEquals(expected, enabledOracle153(stored, sp, ep, sh, eh))
            assertEquals(enabledOracle153(stored, sp, ep, sh, eh), DndPreferenceResolve.enabled(stored, sp, ep, sh, eh))
            check(input)
        }
        val minuteCases = listOf(
            // storedMinute, hour -> expected startMinute
            (null to 5) to 300,
            (90 to 5) to 90,
            (2000 to 5) to 1439,
            (-5 to 5) to 0,
            (null to 25) to 1380,
            (null to -1) to 0,
            (0 to 0) to 0,
            (1439 to 0) to 1439,
            (1440 to 0) to 1439,
            (null to 23) to 1380,
            (null to 0) to 0,
            (Int.MIN_VALUE to 7) to 0,
            (Int.MAX_VALUE to 7) to 1439,
        )
        for ((pair, expected) in minuteCases) {
            val (stored, hour) = pair
            assertEquals(expected, startMinuteOracle153(stored, hour))
            assertEquals(startMinuteOracle153(stored, hour), DndPreferenceResolve.startMinute(stored, hour))
            assertEquals(expected, endMinuteOracle153(stored, hour))
            assertEquals(endMinuteOracle153(stored, hour), DndPreferenceResolve.endMinute(stored, hour))
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(153002)
        repeat(2000) {
            check(randomDndInput153(rnd))
        }
    }

    @Test
    fun sweepExhaustive() {
        // enabled: stored x present flags x clamped hour grid.
        for (stored in listOf<Boolean?>(null, true, false)) {
            for (sp in listOf(false, true)) {
                for (ep in listOf(false, true)) {
                    for (sh in listOf(-1, 0, 1, 22, 23, 24)) {
                        for (eh in listOf(-1, 0, 1, 22, 23, 24)) {
                            check(DndInput153(stored, sp, ep, sh, eh, null, null))
                        }
                    }
                }
            }
        }
        // Minutes: stored-minute grid x hour grid, both ends.
        for (stored in listOf<Int?>(null, -1, 0, 1439, 1440)) {
            for (hour in listOf(-1, 0, 1, 23, 24)) {
                check(DndInput153(null, true, true, hour, hour, stored, stored))
            }
        }
        // Int extremes: clamps must hold at the edges.
        for (hour in listOf(Int.MIN_VALUE, Int.MAX_VALUE)) {
            check(DndInput153(null, true, true, hour, hour, null, null))
            check(DndInput153(null, true, true, hour, 0, null, null))
        }
        for (minute in listOf(Int.MIN_VALUE, Int.MAX_VALUE)) {
            check(DndInput153(null, true, true, 0, 0, minute, minute))
        }
    }
}
