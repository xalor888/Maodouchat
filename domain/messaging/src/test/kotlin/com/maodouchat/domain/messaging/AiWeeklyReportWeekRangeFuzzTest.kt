package com.maodouchat.domain.messaging

import java.util.Calendar
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// Independent re-implementation of currentWeekRange: pure epoch-day
// arithmetic in fixed +08:00 (Asia/Shanghai has no DST in the tested
// range), no Calendar at all, so a slip in either side shows up.
// Epoch day 0 is a Thursday; Monday of the Sunday-start week is
// (weekday+1)%7 days back, plus one -- so a Sunday lands on the
// *next* Monday, not the one six days earlier.
private fun weekRangeOracle192(now: Long): Pair<Long, Long> {
    val localDay = Math.floorDiv(now + 8L * 3_600_000L, 86_400_000L)
    val weekdayMon0 = ((localDay + 3) % 7 + 7) % 7
    val mondayDay = localDay - (weekdayMon0 + 1) % 7 + 1
    val start = mondayDay * 86_400_000L - 8L * 3_600_000L
    return start to start + 7L * 86_400_000L
}

private const val WEEK_START_MS192 = 946_684_800_000L // 2000-01-01T00:00+08
private const val WEEK_END_MS192 = 1_893_456_000_000L // 2030-01-01T00:00+08

private fun randomWeekNow192(r: Random): Long =
    r.nextLong(WEEK_START_MS192, WEEK_END_MS192)

private fun randomBoundaryNow192(r: Random): Long {
    // A Monday 00:00 (+08) somewhere in range, plus a boundary delta.
    val monday = WEEK_START_MS192 + r.nextLong(0, 1_560) * 7L * 86_400_000L
    val delta = when (r.nextInt(10)) {
        0 -> -1L
        1 -> 0L
        2 -> 1L
        3 -> 86_399_999L
        4 -> 86_400_000L
        5 -> 86_400_001L
        6 -> -86_400_000L
        7 -> 604_799_999L
        8 -> 604_800_000L
        else -> 604_800_001L
    }
    return monday + delta
}

// ---------- tests ----------

class AiWeeklyReportWeekRangeFuzzTest {

    @Test
    fun weekRangeDifferential192() {
        val r = Random(192001)
        repeat(300) {
            val now = randomWeekNow192(r)
            val (start, end) = AiWeeklyReportWeekRange.currentWeekRange(now)
            assertEquals(weekRangeOracle192(now), start to end)
            assertEquals(7L * 24L * 60L * 60L * 1000L, end - start)
        }
    }

    @Test
    fun weekRangeBoundaryDifferential192() {
        val r = Random(192002)
        repeat(300) {
            val now = randomBoundaryNow192(r)
            val (start, end) = AiWeeklyReportWeekRange.currentWeekRange(now)
            assertEquals(weekRangeOracle192(now), start to end)
            assertEquals(7L * 24L * 60L * 60L * 1000L, end - start)
        }
    }

    @Test
    fun weekRangePinned192() {
        // Epoch: Thursday 1970-01-01 08:00+08, week starts Monday 1969-12-29.
        assertEquals(
            -288_000_000L to 316_800_000L,
            AiWeeklyReportWeekRange.currentWeekRange(0L)
        )
        assertEquals(
            -288_000_000L to 316_800_000L,
            AiWeeklyReportWeekRange.currentWeekRange(-1L)
        )
        // Sunday 2026-11-22 00:00+08 belongs to the week starting the
        // *next* Monday (Sunday-start weeks under the default locale).
        assertEquals(
            1_795_363_200_000L to 1_795_968_000_000L,
            AiWeeklyReportWeekRange.currentWeekRange(1_795_276_800_000L)
        )
        // Monday 00:00 sharp is its own week start.
        assertEquals(
            1_795_968_000_000L to 1_796_572_800_000L,
            AiWeeklyReportWeekRange.currentWeekRange(1_795_968_000_000L)
        )
        // Saturday 2026-11-28 23:59:59.999 still in the Nov 23 week.
        assertEquals(
            1_795_363_200_000L to 1_795_968_000_000L,
            AiWeeklyReportWeekRange.currentWeekRange(1_795_881_599_999L)
        )
        // Year boundary: Tuesday 2030-01-01 falls in the Dec 31 week.
        assertEquals(
            1_893_340_800_000L to 1_893_945_600_000L,
            AiWeeklyReportWeekRange.currentWeekRange(1_893_427_200_000L)
        )
        // Wednesday 2026-11-04 15:30+08 -> Monday 2026-11-02 00:00+08.
        val wednesday = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 4, 15, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(
            1_793_548_800_000L to 1_794_153_600_000L,
            AiWeeklyReportWeekRange.currentWeekRange(wednesday)
        )
        val (start, _) = AiWeeklyReportWeekRange.currentWeekRange(wednesday)
        val cal = Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(Calendar.MONDAY, cal.get(Calendar.DAY_OF_WEEK))
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
    }
}
