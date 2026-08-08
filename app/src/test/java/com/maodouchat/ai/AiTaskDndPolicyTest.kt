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
}
