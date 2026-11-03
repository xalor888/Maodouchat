package com.maodouchat.core.model

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatListTimeFormatTest {

    private fun cal(y: Int, m: Int, d: Int, h: Int = 12): Calendar =
        Calendar.getInstance().apply {
            clear()
            set(y, m - 1, d, h, 0, 0)
        }

    @Test
    fun `daysBetween counts calendar days not 24h spans`() {
        assertEquals(0, daysBetween(cal(2026, 4, 10, 0), cal(2026, 4, 10, 23)))
        assertEquals(1, daysBetween(cal(2026, 4, 10), cal(2026, 4, 11)))
        assertEquals(1, daysBetween(cal(2026, 4, 30), cal(2026, 5, 1)))
    }

    @Test
    fun `formatChatTime blanks non-positive timestamps`() {
        assertEquals("", formatChatTime(0L))
        assertEquals("", formatChatTime(-1L))
    }
}
