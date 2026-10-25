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

}
