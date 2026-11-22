package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for the time-window cluster (checklist line 931,
 * 49th block): [DndWindowPolicy], [NightWindowPolicy], [SilentUntilPolicy]
 * (+ [SilentUntilOption]). Test-only, zero production changes.
 *
 * The oracle reimplements the policies independently: explicit if/else
 * branches instead of coerceIn, the wrap-around window as the complement of
 * the daytime gap [end, start), manual zero-padding instead of
 * String.format, and a strict > comparison for isActive. Shared primitives
 * (production calls them too, so sharing is exact): Int/Long arithmetic
 * (including silent Long wrap on overflow), Int division truncating toward
 * zero, Pair construction, null checks.
 *
 * Three quirks are pinned, not fixed (behaviour, not bugs):
 * - clampWindow does not reorder: (MAX, MIN) clamps to (1439, 0).
 * - A window with start == end is empty and never matches; the wrap branch
 *   is taken only for start > end.
 * - formatNightWindowHour divides with truncation toward zero, so -60
 *   minutes formats as "-1:00" while -59 formats as "00:00".
 * - deadlineMillis wraps silently: hours = 2562047788015 just fits
 *   (product 9223372036854000000), +1 wraps the product to a negative
 *   deadline; hours = Long.MAX_VALUE wraps the product to -3600000.
 *
 * Verification (offline, JDK 17): the real production code was compiled
 * verbatim with the Kotlin compiler and differentially checked against this
 * oracle on 44,726 cases — the exact inputs these tests generate
 * (Random(42)), a codec round-trip, clamp / window / night-window / format /
 * deadline / isActive exhaustives, and 35,000 Python-generated random lines
 * — with 0 differences. Every pinned expectation below was checked against
 * real production. The 8 test methods were also run locally against the real
 * production code (kotlin-test stubs): all pass.
 */
class TimeWindowPolicyFuzzTest {

    // ---------- oracles ----------

    private fun clampMinuteOfDayOracle(minute: Int): Int {
        if (minute < 0) return 0
        if (minute > 1439) return 1439
        return minute
    }

    private fun clampHourOfDayOracle(hour: Int): Int {
        if (hour < 0) return 0
        if (hour > 23) return 23
        return hour
    }

    private fun clampWindowOracle(startMinute: Int, endMinute: Int): Pair<Int, Int> =
        clampMinuteOfDayOracle(startMinute) to clampMinuteOfDayOracle(endMinute)

    private fun isWithinNightWindowOracle(currentMinute: Int, start: Int, end: Int): Boolean {
        if (start <= end) {
            return currentMinute >= start && currentMinute < end
        }
        // Wrap-around window: inside iff NOT in the daytime gap [end, start).
        return !(currentMinute >= end && currentMinute < start)
    }

    private fun formatNightWindowHourOracle(minutes: Int): String {
        val h = minutes / 60
        if (h < 0) return "$h:00"
        if (h < 10) return "0$h:00"
        return "$h:00"
    }

    private fun deadlineMillisOracle(nowMillis: Long, hours: Long): Long =
        nowMillis + hours * 3600_000L

    private fun isActiveOracle(silentUntilMillis: Long, nowMillis: Long): Boolean =
        silentUntilMillis > nowMillis

    // ---------- generators (Random(42) in the CI tests) ----------

    private val MINUTE_POOL = listOf(
        0, 1, -1, 22, 23, 24, 25, 59, 60, 61, 719, 720, 721,
        1438, 1439, 1440, 1441, 1500, 2880, -100, 100000,
        Int.MAX_VALUE, Int.MIN_VALUE
    )

    private fun randomMinute(rnd: Random): Int {
        return when (rnd.nextInt(3)) {
            0 -> MINUTE_POOL[rnd.nextInt(MINUTE_POOL.size)]
            1 -> rnd.nextInt(-200, 3001)
            else -> {
                val base = listOf(0, 23, 24, 60, 720, 1439, 1440)[rnd.nextInt(7)]
                base + rnd.nextInt(-2, 3)
            }
        }
    }

    private fun randomHour(rnd: Random): Int {
        return when (rnd.nextInt(3)) {
            0 -> listOf(0, 1, -1, 22, 23, 24, 25, 12, -100, 100, Int.MAX_VALUE, Int.MIN_VALUE)[rnd.nextInt(12)]
            1 -> rnd.nextInt(-50, 101)
            else -> listOf(0, 23, 24)[rnd.nextInt(3)] + rnd.nextInt(-2, 3)
        }
    }

    private val LONG_POOL = listOf(
        0L, 1L, -1L, 1000L, 2000L, 5000L, -5000L,
        Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 1000L, Long.MIN_VALUE + 1000L,
        Long.MAX_VALUE - 3600000L, Long.MIN_VALUE + 3600000L
    )

    private fun randomLong(rnd: Random): Long {
        return when (rnd.nextInt(4)) {
            0 -> LONG_POOL[rnd.nextInt(LONG_POOL.size)]
            1 -> rnd.nextLong(-100000L, 100001L)
            2 -> Long.MAX_VALUE - rnd.nextLong(0L, 100001L)
            else -> Long.MIN_VALUE + rnd.nextLong(0L, 100001L)
        }
    }

    // 2562047788015 * 3600_000 just fits in a Long; +1 wraps the product.
    private val HOURS_POOL = listOf(
        0L, 1L, -1L, 8L, 24L, 25L, -24L,
        2562047788015L, 2562047788016L, -2562047788015L, -2562047788016L,
        1000000L, -1000000L, Long.MAX_VALUE, Long.MIN_VALUE
    )

    private fun randomHours(rnd: Random): Long {
        return when (rnd.nextInt(3)) {
            0 -> HOURS_POOL[rnd.nextInt(HOURS_POOL.size)]
            1 -> rnd.nextLong(-100L, 101L)
            else -> listOf(0L, 1L, 8L, 24L, 2562047788015L, 2562047788016L)[rnd.nextInt(6)] +
                rnd.nextLong(-2L, 3L)
        }
    }

    // Field codec (decimal text, "-" = null) lives in Parse49.kt, shared with Probe49.

    @Test
    fun clampMinuteOfDayRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val minute = randomMinute(rnd)
            assertEquals(
                clampMinuteOfDayOracle(minute),
                DndWindowPolicy.clampMinuteOfDay(minute),
                "minute=$minute"
            )
        }
    }

    @Test
    fun clampHourOfDayRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val hour = randomHour(rnd)
            assertEquals(
                clampHourOfDayOracle(hour),
                DndWindowPolicy.clampHourOfDay(hour),
                "hour=$hour"
            )
        }
    }

    @Test
    fun clampWindowRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val start = randomMinute(rnd)
            val end = randomMinute(rnd)
            assertEquals(
                clampWindowOracle(start, end),
                DndWindowPolicy.clampWindow(start, end),
                "start=$start end=$end"
            )
        }
    }

    @Test
    fun isWithinNightWindowRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val current = randomMinute(rnd)
            val start = randomMinute(rnd)
            val end = randomMinute(rnd)
            assertEquals(
                isWithinNightWindowOracle(current, start, end),
                NightWindowPolicy.isWithinNightWindow(current, start, end),
                "current=$current start=$start end=$end"
            )
        }
    }

    @Test
    fun formatNightWindowHourRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val minutes = randomMinute(rnd)
            assertEquals(
                formatNightWindowHourOracle(minutes),
                NightWindowPolicy.formatNightWindowHour(minutes),
                "minutes=$minutes"
            )
        }
    }

    @Test
    fun deadlineMillisRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val now = randomLong(rnd)
            val hours = randomHours(rnd)
            assertEquals(
                deadlineMillisOracle(now, hours),
                SilentUntilPolicy.deadlineMillis(now, hours),
                "now=$now hours=$hours"
            )
        }
    }

    @Test
    fun isActiveRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val silentUntil = randomLong(rnd)
            val now = randomLong(rnd)
            assertEquals(
                isActiveOracle(silentUntil, now),
                SilentUntilPolicy.isActive(silentUntil, now),
                "silentUntil=$silentUntil now=$now"
            )
        }
    }

    @Test
    fun pinnedEdgeCases() {
        // clampMinuteOfDay: boundaries and extremes.
        assertEquals(0, DndWindowPolicy.clampMinuteOfDay(0))
        assertEquals(1439, DndWindowPolicy.clampMinuteOfDay(1439))
        assertEquals(0, DndWindowPolicy.clampMinuteOfDay(-1))
        assertEquals(1439, DndWindowPolicy.clampMinuteOfDay(1440))
        assertEquals(0, DndWindowPolicy.clampMinuteOfDay(Int.MIN_VALUE))
        assertEquals(1439, DndWindowPolicy.clampMinuteOfDay(Int.MAX_VALUE))
        assertEquals(720, DndWindowPolicy.clampMinuteOfDay(720))
        // clampHourOfDay: boundaries and extremes.
        assertEquals(0, DndWindowPolicy.clampHourOfDay(0))
        assertEquals(23, DndWindowPolicy.clampHourOfDay(23))
        assertEquals(0, DndWindowPolicy.clampHourOfDay(-1))
        assertEquals(23, DndWindowPolicy.clampHourOfDay(24))
        assertEquals(0, DndWindowPolicy.clampHourOfDay(Int.MIN_VALUE))
        assertEquals(23, DndWindowPolicy.clampHourOfDay(Int.MAX_VALUE))
        assertEquals(12, DndWindowPolicy.clampHourOfDay(12))
        // clampWindow: no reordering after clamping.
        assertEquals(0 to 1439, DndWindowPolicy.clampWindow(0, 1439))
        assertEquals(0 to 1439, DndWindowPolicy.clampWindow(-5, 2000))
        assertEquals(60 to 120, DndWindowPolicy.clampWindow(60, 120))
        assertEquals(1439 to 0, DndWindowPolicy.clampWindow(Int.MAX_VALUE, Int.MIN_VALUE))
        // isWithinNightWindow: start inclusive, end exclusive, both branches.
        assertTrue(NightWindowPolicy.isWithinNightWindow(9 * 60, 9 * 60, 17 * 60))
        assertFalse(NightWindowPolicy.isWithinNightWindow(17 * 60, 9 * 60, 17 * 60))
        assertTrue(NightWindowPolicy.isWithinNightWindow(22 * 60, 21 * 60, 7 * 60))
        assertTrue(NightWindowPolicy.isWithinNightWindow(6 * 60, 21 * 60, 7 * 60))
        assertFalse(NightWindowPolicy.isWithinNightWindow(12 * 60, 21 * 60, 7 * 60))
        assertFalse(NightWindowPolicy.isWithinNightWindow(7 * 60, 21 * 60, 7 * 60))
        assertTrue(NightWindowPolicy.isWithinNightWindow(0, 21 * 60, 7 * 60))
        assertTrue(NightWindowPolicy.isWithinNightWindow(419, 21 * 60, 7 * 60))
        assertFalse(NightWindowPolicy.isWithinNightWindow(420, 21 * 60, 7 * 60))
        assertTrue(NightWindowPolicy.isWithinNightWindow(21 * 60, 21 * 60, 7 * 60))
        // start == end is an empty window: never matches, even at the point itself.
        assertFalse(NightWindowPolicy.isWithinNightWindow(720, 720, 720))
        assertFalse(NightWindowPolicy.isWithinNightWindow(0, 0, 0))
        assertFalse(NightWindowPolicy.isWithinNightWindow(720, 0, 0))
        // Un-normalized inputs compare raw: outside [0, 1440) never matches.
        assertFalse(NightWindowPolicy.isWithinNightWindow(Int.MAX_VALUE, 0, 1440))
        assertFalse(NightWindowPolicy.isWithinNightWindow(Int.MIN_VALUE, 0, 1440))
        // formatNightWindowHour: padding, overflow past 24h, negative truncation.
        assertEquals("00:00", NightWindowPolicy.formatNightWindowHour(0))
        assertEquals("01:00", NightWindowPolicy.formatNightWindowHour(60))
        assertEquals("05:00", NightWindowPolicy.formatNightWindowHour(5 * 60))
        assertEquals("21:00", NightWindowPolicy.formatNightWindowHour(21 * 60))
        assertEquals("23:00", NightWindowPolicy.formatNightWindowHour(23 * 60 + 59))
        assertEquals("24:00", NightWindowPolicy.formatNightWindowHour(1440))
        assertEquals("25:00", NightWindowPolicy.formatNightWindowHour(1500))
        assertEquals("00:00", NightWindowPolicy.formatNightWindowHour(-1))
        assertEquals("00:00", NightWindowPolicy.formatNightWindowHour(-59))
        assertEquals("-1:00", NightWindowPolicy.formatNightWindowHour(-60))
        assertEquals("-1:00", NightWindowPolicy.formatNightWindowHour(-61))
        assertEquals("-60:00", NightWindowPolicy.formatNightWindowHour(-3600))
        assertEquals("35791394:00", NightWindowPolicy.formatNightWindowHour(Int.MAX_VALUE))
        assertEquals("-35791394:00", NightWindowPolicy.formatNightWindowHour(Int.MIN_VALUE))
        // deadlineMillis: the three option tiers and plain arithmetic.
        assertEquals(1_000_000L + 3_600_000L, SilentUntilPolicy.deadlineMillis(1_000_000L, 1))
        assertEquals(1_000_000L + 8 * 3_600_000L, SilentUntilPolicy.deadlineMillis(1_000_000L, 8))
        assertEquals(1_000_000L + 24 * 3_600_000L, SilentUntilPolicy.deadlineMillis(1_000_000L, 24))
        assertEquals(1_000_000L, SilentUntilPolicy.deadlineMillis(1_000_000L, 0))
        assertEquals(28_801_000L, SilentUntilPolicy.deadlineMillis(1000L, SilentUntilOption.Hours8.hours))
        assertEquals(3, SilentUntilOption.entries.size)
        assertEquals(listOf(1L, 8L, 24L), SilentUntilOption.entries.map { it.hours })
        // deadlineMillis: Long arithmetic wraps silently, no exception, no clamp.
        assertEquals(9223372036854000000L, SilentUntilPolicy.deadlineMillis(0L, 2562047788015L))
        assertEquals(-9223372036851951616L, SilentUntilPolicy.deadlineMillis(0L, 2562047788016L))
        assertEquals(-9223372036854000000L, SilentUntilPolicy.deadlineMillis(0L, -2562047788015L))
        assertEquals(9223372036851951616L, SilentUntilPolicy.deadlineMillis(0L, -2562047788016L))
        assertEquals(-775809L, SilentUntilPolicy.deadlineMillis(Long.MAX_VALUE, 2562047788015L))
        assertEquals(-9223372036851175809L, SilentUntilPolicy.deadlineMillis(Long.MAX_VALUE, 1L))
        assertEquals(9223372036851175808L, SilentUntilPolicy.deadlineMillis(Long.MIN_VALUE, -1L))
        assertEquals(-3600000L, SilentUntilPolicy.deadlineMillis(0L, Long.MAX_VALUE))
        assertEquals(9223372036851175807L, SilentUntilPolicy.deadlineMillis(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(Long.MIN_VALUE, SilentUntilPolicy.deadlineMillis(Long.MIN_VALUE, Long.MIN_VALUE))
        // isActive: strict > ; equal deadlines are not active.
        assertTrue(SilentUntilPolicy.isActive(2_000_000L, 1_000_000L))
        assertFalse(SilentUntilPolicy.isActive(999_999L, 1_000_000L))
        assertFalse(SilentUntilPolicy.isActive(1_000_000L, 1_000_000L))
        assertTrue(SilentUntilPolicy.isActive(Long.MAX_VALUE, Long.MIN_VALUE))
        assertFalse(SilentUntilPolicy.isActive(Long.MIN_VALUE, Long.MAX_VALUE))
    }

}
