package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallHistoryTimeFuzzTest {

    private val random = Random(2026_11_22L)

    // 与现有单测同一锚点；偏移量约束在 [-1 天, +30 天]，now - millis 永不溢出。
    // formatDay 不 fuzz：默认 locale/时区 + ThreadLocal SimpleDateFormat，结果依赖运行环境。
    private val now = 1_000_000_000_000L

    private val boundaryOffsets = listOf(
        0L, 1L, 59_999L, 60_000L, 60_001L,
        3_599_999L, 3_600_000L, 3_600_001L,
        86_399_999L, 86_400_000L, 86_400_001L,
        7L * 86_400_000L - 1L, 7L * 86_400_000L, 7L * 86_400_000L + 1L,
        -1L, -60_000L, -86_400_000L,
    )

    private fun randomOffset(): Long =
        if (random.nextInt(4) == 0) boundaryOffsets[random.nextInt(boundaryOffsets.size)]
        else random.nextLong(-86_400_000L, 30L * 86_400_000L)

    private fun randomDuration(): Long =
        if (random.nextInt(4) == 0) {
            listOf(0L, -1L, -500L, 999L, 1_000L, 59_999L, 60_000L, 3_599_999L, 3_600_000L)[random.nextInt(9)]
        } else {
            random.nextLong(-3_600_000L, 100L * 3_600_000L)
        }

    // 分桶决策树的逐字复述：60s / 1h / 1d / 7d 四阈值，负 diff（未来时间）落 JustNow。
    private fun oracleRelativeTime(nowMillis: Long, millis: Long): CallRelativeTime {
        val diff = nowMillis - millis
        return when {
            diff < 60_000L -> CallRelativeTime.JustNow
            diff < 3_600_000L -> CallRelativeTime.MinutesAgo((diff / 60_000L).toInt())
            diff < 86_400_000L -> CallRelativeTime.HoursAgo((diff / 3_600_000L).toInt())
            diff < 7L * 86_400_000L -> CallRelativeTime.DaysAgo((diff / 86_400_000L).toInt())
            else -> CallRelativeTime.Date(millis)
        }
    }

    private fun oracleFormatDuration(durationMs: Long): String {
        if (durationMs <= 0L) return ""
        val seconds = durationMs / 1000L
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    @Test
    fun `relativeTime matches verbatim oracle over random offsets`() {
        repeat(200) {
            val millis = now - randomOffset()
            assertEquals(oracleRelativeTime(now, millis), CallHistoryTime.relativeTime(now, millis), "millis=$millis")
        }
    }

    @Test
    fun `relativeTime matches oracle over random future times`() {
        repeat(100) {
            val millis = now + random.nextLong(1L, 30L * 86_400_000L)
            assertEquals(CallRelativeTime.JustNow, CallHistoryTime.relativeTime(now, millis), "millis=$millis")
        }
    }

    @Test
    fun `relativeTime bucket boundaries are pinned`() {
        val cases = listOf(
            -86_400_000L to CallRelativeTime.JustNow,
            -1L to CallRelativeTime.JustNow,
            0L to CallRelativeTime.JustNow,
            59_999L to CallRelativeTime.JustNow,
            60_000L to CallRelativeTime.MinutesAgo(1),
            3_599_999L to CallRelativeTime.MinutesAgo(59),
            3_600_000L to CallRelativeTime.HoursAgo(1),
            86_399_999L to CallRelativeTime.HoursAgo(23),
            86_400_000L to CallRelativeTime.DaysAgo(1),
            7L * 86_400_000L - 1L to CallRelativeTime.DaysAgo(6),
            7L * 86_400_000L to CallRelativeTime.Date(now - 7L * 86_400_000L),
            30L * 86_400_000L to CallRelativeTime.Date(now - 30L * 86_400_000L),
        )
        for ((offset, expected) in cases) {
            assertEquals(expected, CallHistoryTime.relativeTime(now, now - offset), "offset=$offset")
        }
    }

    @Test
    fun `minutesAgo range is one to fifty-nine over random offsets`() {
        repeat(200) {
            val result = CallHistoryTime.relativeTime(now, now - randomOffset())
            if (result is CallRelativeTime.MinutesAgo) assertTrue(result.minutes in 1..59, "result=$result")
        }
    }

    @Test
    fun `hoursAgo range is one to twenty-three over random offsets`() {
        repeat(200) {
            val result = CallHistoryTime.relativeTime(now, now - randomOffset())
            if (result is CallRelativeTime.HoursAgo) assertTrue(result.hours in 1..23, "result=$result")
        }
    }

    @Test
    fun `daysAgo range is one to six over random offsets`() {
        repeat(200) {
            val result = CallHistoryTime.relativeTime(now, now - randomOffset())
            if (result is CallRelativeTime.DaysAgo) assertTrue(result.days in 1..6, "result=$result")
        }
    }

    @Test
    fun `date bucket only appears at or beyond seven days and carries the input millis`() {
        repeat(200) {
            val offset = randomOffset()
            val millis = now - offset
            val result = CallHistoryTime.relativeTime(now, millis)
            if (result is CallRelativeTime.Date) {
                assertTrue(offset >= 7L * 86_400_000L, "offset=$offset result=$result")
                assertEquals(millis, result.millis, "offset=$offset")
            } else {
                assertTrue(offset < 7L * 86_400_000L, "offset=$offset result=$result")
            }
        }
    }

    @Test
    fun `same offset always yields the same bucket`() {
        repeat(100) {
            val millis = now - randomOffset()
            assertEquals(
                CallHistoryTime.relativeTime(now, millis),
                CallHistoryTime.relativeTime(now, millis),
                "millis=$millis",
            )
        }
    }

    @Test
    fun `future time is always just now`() {
        for (ahead in listOf(1L, 60_000L, 3_600_000L, 86_400_000L, 30L * 86_400_000L)) {
            assertEquals(CallRelativeTime.JustNow, CallHistoryTime.relativeTime(now, now + ahead), "ahead=$ahead")
        }
    }

    @Test
    fun `minutes bucket increments at minute marks`() {
        for (minutes in 1..59) {
            val atMark = now - minutes * 60_000L
            assertEquals(CallRelativeTime.MinutesAgo(minutes), CallHistoryTime.relativeTime(now, atMark))
            val justBefore = now - (minutes * 60_000L - 1L)
            val expected = if (minutes == 1) CallRelativeTime.JustNow else CallRelativeTime.MinutesAgo(minutes - 1)
            assertEquals(expected, CallHistoryTime.relativeTime(now, justBefore))
        }
    }

    @Test
    fun `hours bucket increments at hour marks`() {
        for (hours in 1..23) {
            val atMark = now - hours * 3_600_000L
            assertEquals(CallRelativeTime.HoursAgo(hours), CallHistoryTime.relativeTime(now, atMark))
            val justBefore = now - (hours * 3_600_000L - 1L)
            val expected = if (hours == 1) CallRelativeTime.MinutesAgo(59) else CallRelativeTime.HoursAgo(hours - 1)
            assertEquals(expected, CallHistoryTime.relativeTime(now, justBefore))
        }
    }

    @Test
    fun `days bucket increments at day marks`() {
        for (days in 1..6) {
            val atMark = now - days * 86_400_000L
            assertEquals(CallRelativeTime.DaysAgo(days), CallHistoryTime.relativeTime(now, atMark))
            val justBefore = now - (days * 86_400_000L - 1L)
            val expected = if (days == 1) CallRelativeTime.HoursAgo(23) else CallRelativeTime.DaysAgo(days - 1)
            assertEquals(expected, CallHistoryTime.relativeTime(now, justBefore))
        }
    }

    @Test
    fun `formatDuration matches oracle over random durations`() {
        repeat(200) {
            val duration = randomDuration()
            assertEquals(oracleFormatDuration(duration), CallHistoryTime.formatDuration(duration), "duration=$duration")
        }
    }

    @Test
    fun `formatDuration matches oracle over random non-positive durations`() {
        repeat(100) {
            val duration = -random.nextLong(0L, 10_000_000L)
            assertEquals("", CallHistoryTime.formatDuration(duration), "duration=$duration")
        }
    }

    @Test
    fun `formatDuration boundary table is pinned`() {
        val cases = listOf(
            -1L to "",
            0L to "",
            999L to "0:00",
            1_000L to "0:01",
            59_999L to "0:59",
            60_000L to "1:00",
            61_500L to "1:01",
            3_599_999L to "59:59",
            3_600_000L to "60:00",
            86_400_000L to "1440:00",
            3_600_000_000L to "60000:00",
        )
        for ((duration, expected) in cases) {
            assertEquals(expected, CallHistoryTime.formatDuration(duration), "duration=$duration")
        }
    }

    @Test
    fun `formatDuration never emits a negative sign or empty string for positive input`() {
        repeat(200) {
            val duration = random.nextLong(1L, 100L * 3_600_000L)
            val formatted = CallHistoryTime.formatDuration(duration)
            assertTrue(formatted.isNotEmpty() && !formatted.startsWith("-"), "duration=$duration formatted=$formatted")
        }
    }

    @Test
    fun `formatDuration seconds field is always two digits`() {
        repeat(200) {
            val duration = randomDuration()
            if (duration <= 0L) return@repeat
            val parts = CallHistoryTime.formatDuration(duration).split(":")
            assertEquals(2, parts.size, "duration=$duration")
            assertEquals(2, parts[1].length, "duration=$duration formatted=${parts.joinToString(":")}")
        }
    }

    @Test
    fun `formatDuration minutes field grows past sixty without rolling over`() {
        val cases = listOf(
            61L * 60_000L to "61:00",
            119L * 60_000L + 59_000L to "119:59",
            120L * 60_000L to "120:00",
        )
        for ((duration, expected) in cases) {
            assertEquals(expected, CallHistoryTime.formatDuration(duration), "duration=$duration")
        }
    }

    @Test
    fun `formatDuration sub-second part is truncated not rounded`() {
        val cases = listOf(
            1_999L to "0:01",
            60_999L to "1:00",
            3_599_999L to "59:59",
        )
        for ((duration, expected) in cases) {
            assertEquals(expected, CallHistoryTime.formatDuration(duration), "duration=$duration")
        }
    }

    @Test
    fun `same duration always formats the same`() {
        repeat(100) {
            val duration = randomDuration()
            assertEquals(
                CallHistoryTime.formatDuration(duration),
                CallHistoryTime.formatDuration(duration),
                "duration=$duration",
            )
        }
    }
}
