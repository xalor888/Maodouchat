package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTaskDndPolicyTest {

    @Test
    fun `overnight window covers both sides of midnight`() {
        assertTrue(AiTaskDndPolicy.isInQuietHours(1350, 1350, 420))
        assertTrue(AiTaskDndPolicy.isInQuietHours(419, 1350, 420))
        assertFalse(AiTaskDndPolicy.isInQuietHours(420, 1350, 420))
    }

    @Test
    fun `empty window never suppresses`() {
        assertFalse(AiTaskDndPolicy.isInQuietHours(100, 60, 60))
        assertNull(AiTaskDndPolicy.nextAllowedTime(1_000L, 100, 60, 60, 2_000L))
    }

    @Test
    fun `next allowed time is the window end when inside`() {
        assertEquals(5_000L, AiTaskDndPolicy.nextAllowedTime(1_000L, 750, 720, 780, 5_000L))
        assertNull(AiTaskDndPolicy.nextAllowedTime(1_000L, 800, 720, 780, 5_000L))
    }

    @Test
    fun `reminders require both master switches`() {
        assertTrue(AiTaskDndPolicy.remindersAllowed(true, true))
        assertFalse(AiTaskDndPolicy.remindersAllowed(false, true))
    }
}
