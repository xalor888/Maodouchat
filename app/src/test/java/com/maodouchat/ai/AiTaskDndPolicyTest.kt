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
}
