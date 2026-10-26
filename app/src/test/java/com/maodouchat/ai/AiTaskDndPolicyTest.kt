package com.maodouchat.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTaskDndPolicyTest {
    @Test
    fun `disabled window when start equals end`() {
        assertFalse(AiTaskDndPolicy.isInQuietHours(23, 22, 22))
        assertNull(AiTaskDndPolicy.nextAllowedTime(1_000L, 23, 22, 22, 2_000L))
    }

    @Test
    fun `overnight quiet hours`() {
        assertTrue(AiTaskDndPolicy.isInQuietHours(23, 22, 7))
        assertTrue(AiTaskDndPolicy.isInQuietHours(3, 22, 7))
        assertFalse(AiTaskDndPolicy.isInQuietHours(10, 22, 7))
        assertEquals(2_000L, AiTaskDndPolicy.nextAllowedTime(1_000L, 23, 22, 7, 2_000L))
        assertNull(AiTaskDndPolicy.nextAllowedTime(1_000L, 10, 22, 7, 2_000L))
    }

    @Test
    fun `same-day quiet hours`() {
        assertTrue(AiTaskDndPolicy.isInQuietHours(13, 12, 14))
        assertFalse(AiTaskDndPolicy.isInQuietHours(11, 12, 14))
        assertFalse(AiTaskDndPolicy.isInQuietHours(14, 12, 14))
    }

    @Test
    fun `reminders require both master switches`() {
        assertTrue(AiTaskDndPolicy.remindersAllowed(true, true))
        assertFalse(AiTaskDndPolicy.remindersAllowed(true, false))
        assertFalse(AiTaskDndPolicy.remindersAllowed(false, true))
    }

    @Test
    fun `runtime switch off means never quiet`() {
        // dndRuntimeEnabled=false：窗口再大也不抑制。
        assertFalse(AiTaskDndPolicy.isInQuietHours(23, 22, 7, dndRuntimeEnabled = false))
    }

    @Test
    fun `minute of day and bounds are clamped before window math`() {
        assertTrue(AiTaskDndPolicy.isInQuietHours(-5, 0, 60))
        assertFalse(AiTaskDndPolicy.isInQuietHours(1500, 0, 60))
        assertTrue(AiTaskDndPolicy.isInQuietHours(30, -100, 60))
    }

    @Test
    fun `expired window end means allowed now`() {
        // 在勿扰窗口内但 endOfWindowMs 已过：直接允许投递。
        assertNull(AiTaskDndPolicy.nextAllowedTime(5_000L, 23, 22, 7, 4_000L))
    }

    @Test
    fun `overnight window is start inclusive end exclusive`() {
        assertTrue(AiTaskDndPolicy.isInQuietHours(1350, 1350, 420))
        assertTrue(AiTaskDndPolicy.isInQuietHours(419, 1350, 420))
        assertFalse(AiTaskDndPolicy.isInQuietHours(420, 1350, 420))
    }

    @Test
    fun `both switches off still denies reminders`() {
        assertFalse(AiTaskDndPolicy.remindersAllowed(false, false))
    }

    @Test
    fun `nextAllowedTime requires endOfWindowMs strictly after nowMs`() {
        assertNull(AiTaskDndPolicy.nextAllowedTime(1_000L, 23, 22, 7, 1_000L))
        assertEquals(1_001L, AiTaskDndPolicy.nextAllowedTime(1_000L, 23, 22, 7, 1_001L))
    }

    @Test
    fun `same-day window is start inclusive end exclusive`() {
        assertTrue(AiTaskDndPolicy.isInQuietHours(0, 0, 1439))
        assertFalse(AiTaskDndPolicy.isInQuietHours(1439, 0, 1439))
        assertTrue(AiTaskDndPolicy.isInQuietHours(12, 12, 14))
    }

    @Test
    fun `clamped bounds can empty a window or pull a minute into it`() {
        assertFalse(AiTaskDndPolicy.isInQuietHours(30, 1500, 1600))
        assertTrue(AiTaskDndPolicy.isInQuietHours(0, -100, 1500))
        assertEquals(2_000L, AiTaskDndPolicy.nextAllowedTime(1_000L, -5, 0, 60, 2_000L))
    }

    @Test
    fun `overnight window boundaries hug midnight`() {
        assertFalse(AiTaskDndPolicy.isInQuietHours(1379, 1380, 60))
        assertTrue(AiTaskDndPolicy.isInQuietHours(1380, 1380, 60))
        assertTrue(AiTaskDndPolicy.isInQuietHours(1439, 1380, 60))
        assertTrue(AiTaskDndPolicy.isInQuietHours(0, 1380, 60))
        assertTrue(AiTaskDndPolicy.isInQuietHours(59, 1380, 60))
        assertFalse(AiTaskDndPolicy.isInQuietHours(60, 1380, 60))
    }

    @Test
    fun `nextAllowedTime honors the start edge`() {
        assertEquals(5_000L, AiTaskDndPolicy.nextAllowedTime(1_000L, 12, 12, 14, 5_000L))
        assertNull(AiTaskDndPolicy.nextAllowedTime(1_000L, 14, 12, 14, 5_000L))
    }

    @Test
    fun `single-minute same-day window`() {
        assertTrue(AiTaskDndPolicy.isInQuietHours(0, 0, 1))
        assertFalse(AiTaskDndPolicy.isInQuietHours(1, 0, 1))
    }

    @Test
    fun `single-minute window at day end`() {
        assertTrue(AiTaskDndPolicy.isInQuietHours(1438, 1438, 1439))
        assertFalse(AiTaskDndPolicy.isInQuietHours(1439, 1438, 1439))
    }

    @Test
    fun `degenerate overnight window covers only its start minute`() {
        // 1439→0：start > end 走跨夜分支，但 end=0 使窗口只剩 1439 这一分钟。
        assertTrue(AiTaskDndPolicy.isInQuietHours(1439, 1439, 0))
        assertFalse(AiTaskDndPolicy.isInQuietHours(0, 1439, 0))
        assertFalse(AiTaskDndPolicy.isInQuietHours(1438, 1439, 0))
    }

    @Test
    fun `clamped bounds collapsing to equal empties the window`() {
        // 越界起止都被钳到 1439：相等即空窗口，连钳制点本身也不抑制。
        assertFalse(AiTaskDndPolicy.isInQuietHours(1439, 1500, 1440))
        assertFalse(AiTaskDndPolicy.isInQuietHours(0, 1500, 1440))
    }

    @Test
    fun `nextAllowedTime respects overnight start and end edges`() {
        assertEquals(5_000L, AiTaskDndPolicy.nextAllowedTime(1_000L, 1350, 1350, 420, 5_000L))
        assertNull(AiTaskDndPolicy.nextAllowedTime(1_000L, 420, 1350, 420, 5_000L))
    }

    @Test
    fun `clamped negative bounds collapse to an empty window`() {
        // 起止都被钳到 0：相等即空窗口，连钳制点本身也不抑制。
        assertFalse(AiTaskDndPolicy.isInQuietHours(0, -100, -50))
        assertFalse(AiTaskDndPolicy.isInQuietHours(30, -100, -50))
    }

    @Test
    fun `inverted bounds always mean overnight regardless of hour`() {
        // 分支只看起止大小：100→60 不是空窗口，而是跨夜窗口（早晨段在内）。
        assertTrue(AiTaskDndPolicy.isInQuietHours(30, 100, 60))
        assertFalse(AiTaskDndPolicy.isInQuietHours(80, 100, 60))
        assertTrue(AiTaskDndPolicy.isInQuietHours(100, 100, 60))
    }

    @Test
    fun `nextAllowedTime inside inverted window returns its end`() {
        assertEquals(5_000L, AiTaskDndPolicy.nextAllowedTime(1_000L, 30, 100, 60, 5_000L))
        assertNull(AiTaskDndPolicy.nextAllowedTime(1_000L, 80, 100, 60, 5_000L))
    }

    @Test
    fun `same-day outside window returns null even with a future end`() {
        assertNull(AiTaskDndPolicy.nextAllowedTime(1_000L, 10, 12, 14, 5_000L))
        assertNull(AiTaskDndPolicy.nextAllowedTime(1_000L, 11, 12, 14, 5_000L))
    }

    @Test
    fun `clamped day-end minute never falls inside an end-exclusive window`() {
        // 1500 被钳到 1439，而窗口右端恒开：[1400, 1439) 与 [1438, 1439) 都不含它。
        assertFalse(AiTaskDndPolicy.isInQuietHours(1500, 1400, 1439))
        assertFalse(AiTaskDndPolicy.isInQuietHours(1500, 1438, 1439))
    }

    @Test
    fun `negative nowMs still honors a future window end`() {
        assertEquals(5_000L, AiTaskDndPolicy.nextAllowedTime(-1_000L, 23, 22, 7, 5_000L))
        assertNull(AiTaskDndPolicy.nextAllowedTime(-1_000L, 10, 22, 7, 5_000L))
    }

    @Test
    fun `negative minute clamps to midnight before window math`() {
        assertFalse(AiTaskDndPolicy.isInQuietHours(-5, 30, 60))
        assertTrue(AiTaskDndPolicy.isInQuietHours(-5, 0, 1))
    }

    @Test
    fun `same-day window starting at minute one excludes midnight`() {
        assertFalse(AiTaskDndPolicy.isInQuietHours(0, 1, 1439))
        assertTrue(AiTaskDndPolicy.isInQuietHours(1, 1, 1439))
        assertTrue(AiTaskDndPolicy.isInQuietHours(1438, 1, 1439))
    }

    @Test
    fun `nextAllowedTime inside inverted window returns end from its start edge`() {
        assertEquals(5_000L, AiTaskDndPolicy.nextAllowedTime(1_000L, 100, 100, 60, 5_000L))
        assertNull(AiTaskDndPolicy.nextAllowedTime(1_000L, 60, 100, 60, 5_000L))
    }

    @Test
    fun `clamped start can turn a range into an overnight window`() {
        // 起边 1500 被钳到 1439，大于终边 60：走跨夜分支，30 与 1439 在内、100 在外。
        assertTrue(AiTaskDndPolicy.isInQuietHours(30, 1500, 60))
        assertFalse(AiTaskDndPolicy.isInQuietHours(100, 1500, 60))
        assertTrue(AiTaskDndPolicy.isInQuietHours(1439, 1500, 60))
    }


    @Test
    fun `clamped minute above day end falls into overnight window`() {
        assertTrue(AiTaskDndPolicy.isInQuietHours(1500, 1380, 60))
    }

    @Test
    fun `negative nowMs still returns end in overnight window`() {
        assertEquals(3_000L, AiTaskDndPolicy.nextAllowedTime(-5_000L, 1439, 1380, 60, 3_000L))
        assertNull(AiTaskDndPolicy.nextAllowedTime(-5_000L, 100, 1380, 60, 3_000L))
    }

    @Test
    fun `overnight window ending at 1438 excludes only that minute`() {
        assertFalse(AiTaskDndPolicy.isInQuietHours(1438, 1439, 1438))
        assertTrue(AiTaskDndPolicy.isInQuietHours(1439, 1439, 1438))
        assertTrue(AiTaskDndPolicy.isInQuietHours(0, 1439, 1438))
        assertTrue(AiTaskDndPolicy.isInQuietHours(1437, 1439, 1438))
    }

    @Test
    fun `outside overnight morning segment returns null despite future end`() {
        assertNull(AiTaskDndPolicy.nextAllowedTime(1_000L, 100, 1380, 60, 5_000L))
    }

    @Test
    fun `inverted window end minute is exclusive`() {
        assertFalse(AiTaskDndPolicy.isInQuietHours(60, 100, 60))
        assertTrue(AiTaskDndPolicy.isInQuietHours(59, 100, 60))
    }


    @Test
    fun `runtime switch off means never quiet in same-day window`() {
        assertFalse(AiTaskDndPolicy.isInQuietHours(13, 12, 14, dndRuntimeEnabled = false))
    }

    @Test
    fun `equal bounds at midnight mean an empty window`() {
        assertFalse(AiTaskDndPolicy.isInQuietHours(0, 0, 0))
        assertFalse(AiTaskDndPolicy.isInQuietHours(1439, 0, 0))
        assertNull(AiTaskDndPolicy.nextAllowedTime(1_000L, 0, 0, 0, 2_000L))
    }

    @Test
    fun `nextAllowedTime same-day end edge one millisecond after now`() {
        assertEquals(1_001L, AiTaskDndPolicy.nextAllowedTime(1_000L, 12, 12, 14, 1_001L))
    }

    @Test
    fun `clamped end to midnight leaves only the evening segment`() {
        // 终边 -50 被钳到 0：跨夜窗口只剩晚间段，凌晨段为空。
        assertTrue(AiTaskDndPolicy.isInQuietHours(1439, 100, -50))
        assertFalse(AiTaskDndPolicy.isInQuietHours(30, 100, -50))
        assertEquals(2_000L, AiTaskDndPolicy.nextAllowedTime(1_000L, 1439, 100, -50, 2_000L))
    }

    @Test
    fun `full-day window covers midday and late minutes`() {
        assertTrue(AiTaskDndPolicy.isInQuietHours(720, 0, 1439))
        assertTrue(AiTaskDndPolicy.isInQuietHours(1438, 0, 1439))
    }

    @Test
    fun `remindersAllowed is exactly the two-switch conjunction`() {
        for (task in listOf(true, false)) {
            for (notif in listOf(true, false)) {
                assertEquals(
                    task && notif,
                    AiTaskDndPolicy.remindersAllowed(task, notif)
                )
            }
        }
    }

    @Test
    fun `nextAllowedTime from the overnight early-hours segment returns end`() {
        assertEquals(5_000L, AiTaskDndPolicy.nextAllowedTime(1_000L, 30, 1380, 60, 5_000L))
        assertNull(AiTaskDndPolicy.nextAllowedTime(1_000L, 60, 1380, 60, 5_000L))
        assertEquals(5_000L, AiTaskDndPolicy.nextAllowedTime(1_000L, 0, 1380, 60, 5_000L))
    }

    @Test
    fun `degenerate overnight window ending at minute one`() {
        assertTrue(AiTaskDndPolicy.isInQuietHours(1439, 1439, 1))
        assertTrue(AiTaskDndPolicy.isInQuietHours(0, 1439, 1))
        assertFalse(AiTaskDndPolicy.isInQuietHours(1, 1439, 1))
        assertFalse(AiTaskDndPolicy.isInQuietHours(100, 1439, 1))
    }

    @Test
    fun `full-day window excludes minute 1439 at its end edge`() {
        assertFalse(AiTaskDndPolicy.isInQuietHours(1439, 0, 1439))
        assertNull(AiTaskDndPolicy.nextAllowedTime(1_000L, 1439, 0, 1439, 5_000L))
        assertTrue(AiTaskDndPolicy.isInQuietHours(0, 0, 1439))
    }

    @Test
    fun `nextAllowedTime midday in full-day window returns end`() {
        assertEquals(5_000L, AiTaskDndPolicy.nextAllowedTime(1_000L, 720, 0, 1439, 5_000L))
        assertEquals(5_000L, AiTaskDndPolicy.nextAllowedTime(1_000L, 1438, 0, 1439, 5_000L))
    }

    @Test
    fun `same-day window sweep covers exactly its minute count`() {
        val inside = (0..1439).count { AiTaskDndPolicy.isInQuietHours(it, 600, 660) }
        assertEquals(60, inside)
    }

    @Test
    fun `overnight window sweep covers evening plus morning segments`() {
        val inside = (0..1439).count { AiTaskDndPolicy.isInQuietHours(it, 1380, 60) }
        assertEquals(120, inside)
    }

    @Test
    fun `nextAllowedTime compares end against now even at long extremes`() {
        assertEquals(Long.MAX_VALUE, AiTaskDndPolicy.nextAllowedTime(1_000L, 23, 22, 7, Long.MAX_VALUE))
        assertNull(AiTaskDndPolicy.nextAllowedTime(Long.MAX_VALUE, 23, 22, 7, Long.MAX_VALUE))
        assertNull(AiTaskDndPolicy.nextAllowedTime(1_000L, 23, 22, 7, -5_000L))
    }

    @Test
    fun `runtime switch off short-circuits before clamping math`() {
        assertTrue(AiTaskDndPolicy.isInQuietHours(30, 1500, 60))
        assertFalse(AiTaskDndPolicy.isInQuietHours(30, 1500, 60, dndRuntimeEnabled = false))
    }

    @Test
    fun `negative timeline still returns an end after now`() {
        assertEquals(-4_000L, AiTaskDndPolicy.nextAllowedTime(-5_000L, 23, 22, 7, -4_000L))
        assertNull(AiTaskDndPolicy.nextAllowedTime(-5_000L, 23, 22, 7, -6_000L))
    }

    @Test
    fun `degenerate overnight window sweep counts two minutes`() {
        val inside = (0..1439).count { AiTaskDndPolicy.isInQuietHours(it, 1439, 1) }
        assertEquals(2, inside)
    }

    @Test
    fun `clamped bounds sweep counts the clamped range`() {
        val inside = (0..1439).count { AiTaskDndPolicy.isInQuietHours(it, -100, 61) }
        assertEquals(61, inside)
    }

    @Test
    fun `inverted window sweep counts evening plus morning`() {
        val inside = (0..1439).count { AiTaskDndPolicy.isInQuietHours(it, 1000, 200) }
        assertEquals(640, inside)
    }

    @Test
    fun `int extreme minutes clamp before window math`() {
        // Int.MIN_VALUE 钳到 0，Int.MAX_VALUE 钳到 1439。
        assertTrue(AiTaskDndPolicy.isInQuietHours(Int.MIN_VALUE, 0, 60))
        assertFalse(AiTaskDndPolicy.isInQuietHours(Int.MIN_VALUE, 30, 60))
        assertFalse(AiTaskDndPolicy.isInQuietHours(Int.MAX_VALUE, 0, 60))
        assertTrue(AiTaskDndPolicy.isInQuietHours(Int.MAX_VALUE, 1380, 60))
    }

    @Test
    fun `full-day window sweep counts all but the end minute`() {
        val inside = (0..1439).count { AiTaskDndPolicy.isInQuietHours(it, 0, 1439) }
        assertEquals(1439, inside)
    }

    @Test
    fun `overnight window sweep counts a narrow evening plus morning span`() {
        val inside = (0..1439).count { AiTaskDndPolicy.isInQuietHours(it, 1200, 120) }
        assertEquals(360, inside)
    }

    @Test
    fun `same-day window sweep counts a short midday span`() {
        val inside = (0..1439).count { AiTaskDndPolicy.isInQuietHours(it, 300, 315) }
        assertEquals(15, inside)
    }

    @Test
    fun `nextAllowedTime outside an inverted window returns null at the end edge`() {
        assertNull(AiTaskDndPolicy.nextAllowedTime(1_000L, 500, 1000, 200, 5_000L))
        assertNull(AiTaskDndPolicy.nextAllowedTime(1_000L, 200, 1000, 200, 5_000L))
        assertEquals(5_000L, AiTaskDndPolicy.nextAllowedTime(1_000L, 199, 1000, 200, 5_000L))
    }

    @Test
    fun `clamped start at zero with end at midnight is an empty window`() {
        assertFalse(AiTaskDndPolicy.isInQuietHours(0, -100, 0))
        assertFalse(AiTaskDndPolicy.isInQuietHours(1439, -100, 0))
        assertFalse(AiTaskDndPolicy.isInQuietHours(720, -100, 0))
        assertNull(AiTaskDndPolicy.nextAllowedTime(1_000L, 0, -100, 0, 2_000L))
    }

    @Test
    fun `int extreme minutes clamp before nextAllowedTime window math`() {
        assertEquals(2_000L, AiTaskDndPolicy.nextAllowedTime(1_000L, Int.MAX_VALUE, 1380, 60, 2_000L))
        assertEquals(2_000L, AiTaskDndPolicy.nextAllowedTime(1_000L, Int.MIN_VALUE, 1380, 60, 2_000L))
        assertNull(AiTaskDndPolicy.nextAllowedTime(1_000L, Int.MIN_VALUE, 600, 660, 2_000L))
    }

    @Test
    fun `midnight start morning window covers only before noon`() {
        assertTrue(AiTaskDndPolicy.isInQuietHours(0, 0, 720))
        assertTrue(AiTaskDndPolicy.isInQuietHours(719, 0, 720))
        assertFalse(AiTaskDndPolicy.isInQuietHours(720, 0, 720))
        assertFalse(AiTaskDndPolicy.isInQuietHours(1439, 0, 720))
    }

    @Test
    fun `nextAllowedTime same-day end equal to now returns null`() {
        assertNull(AiTaskDndPolicy.nextAllowedTime(1_000L, 13, 12, 14, 1_000L))
        assertEquals(1_001L, AiTaskDndPolicy.nextAllowedTime(1_000L, 13, 12, 14, 1_001L))
    }

    @Test
    fun `dnd flag off empties even a full-day window`() {
        assertFalse(AiTaskDndPolicy.isInQuietHours(720, 0, 1439, dndRuntimeEnabled = false))
        assertFalse(AiTaskDndPolicy.isInQuietHours(0, 0, 1439, dndRuntimeEnabled = false))
    }

    @Test
    fun `nextAllowedTime same-day end edge minute returns null despite future end`() {
        assertNull(AiTaskDndPolicy.nextAllowedTime(1_000L, 14, 12, 14, 5_000L))
        assertNull(AiTaskDndPolicy.nextAllowedTime(1_000L, 15, 12, 14, 5_000L))
    }

    @Test
    fun `morning window sweep counts exactly its minutes`() {
        val inside = (0..1439).count { AiTaskDndPolicy.isInQuietHours(it, 0, 720) }
        assertEquals(720, inside)
    }

    @Test
    fun `overnight window 1320 to 360 boundary quartet`() {
        assertFalse(AiTaskDndPolicy.isInQuietHours(1319, 1320, 360))
        assertTrue(AiTaskDndPolicy.isInQuietHours(1320, 1320, 360))
        assertTrue(AiTaskDndPolicy.isInQuietHours(359, 1320, 360))
        assertFalse(AiTaskDndPolicy.isInQuietHours(360, 1320, 360))
    }

    @Test
    fun `same-day window 480 to 540 boundary quartet`() {
        assertFalse(AiTaskDndPolicy.isInQuietHours(479, 480, 540))
        assertTrue(AiTaskDndPolicy.isInQuietHours(480, 480, 540))
        assertTrue(AiTaskDndPolicy.isInQuietHours(539, 480, 540))
        assertFalse(AiTaskDndPolicy.isInQuietHours(540, 480, 540))
    }

    @Test
    fun `nextAllowedTime clamps out-of-range minutes into window`() {
        // 1440 被钳到 1439，落入晚间段；-1 被钳到 0，落入凌晨段：钳制发生在窗口判定之前。
        assertEquals(5_000L, AiTaskDndPolicy.nextAllowedTime(1_000L, 1440, 1400, 100, 5_000L))
        assertEquals(5_000L, AiTaskDndPolicy.nextAllowedTime(1_000L, -1, 1400, 100, 5_000L))
    }

    @Test
    fun `overnight window sweep counts 1320 to 360 span`() {
        val inside = (0..1439).count { AiTaskDndPolicy.isInQuietHours(it, 1320, 360) }
        assertEquals(480, inside)
    }

    @Test
    fun `nextAllowedTime with clamped equal bounds returns null`() {
        // 起止钳后相等即空窗口：钳制点本身也不抑制，未来 end 也返回空。
        assertNull(AiTaskDndPolicy.nextAllowedTime(1_000L, 1439, 1500, 1440, 5_000L))
        assertNull(AiTaskDndPolicy.nextAllowedTime(1_000L, 0, -100, -50, 5_000L))
    }

}
