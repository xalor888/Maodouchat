package com.maodouchat.domain.conversation

import com.maodouchat.core.model.QuietWindow
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatQuietHoursPolicyTest {

    private fun window(enabled: Boolean = true, start: Int = 0, end: Int = 0) =
        QuietWindow(enabled = enabled, startMinute = start, endMinute = end)

    @Test
    fun `disabled window never suppresses`() {
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(QuietWindow.OFF, 720))
    }

    @Test
    fun `start equals end means closed`() {
        val w = QuietWindow(true, 540, 540)
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(w, 540))
    }

    @Test
    fun `overnight window wraps past midnight`() {
        val w = QuietWindow(true, 1320, 420)
        assertTrue(ChatQuietHoursPolicy.shouldSuppress(w, 0))
        assertTrue(ChatQuietHoursPolicy.shouldSuppress(w, 419))
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(w, 420))
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(w, 1319))
    }

    @Test
    fun `quiet hours suppress inside same day window`() {
        val w = window(start = 22 * 60, end = 23 * 60)
        assertTrue(ChatQuietHoursPolicy.shouldSuppress(w, 22 * 60))
        assertTrue(ChatQuietHoursPolicy.shouldSuppress(w, 22 * 60 + 30))
        // 半开区间：end 分钟本身不抑制
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(w, 23 * 60))
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(w, 12 * 60))
    }

    @Test
    fun `quiet hours wrap across midnight`() {
        val w = window(start = 23 * 60, end = 7 * 60)
        assertTrue(ChatQuietHoursPolicy.shouldSuppress(w, 23 * 60 + 30))
        assertTrue(ChatQuietHoursPolicy.shouldSuppress(w, 0))
        assertTrue(ChatQuietHoursPolicy.shouldSuppress(w, 6 * 60 + 59))
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(w, 7 * 60))
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(w, 12 * 60))
    }

    @Test
    fun `quiet hours disabled or zero length never suppress`() {
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(window(enabled = false, start = 0, end = 1439), 12 * 60))
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(window(start = 9 * 60, end = 9 * 60), 9 * 60))
    }

    @Test
    fun `quiet hours clamps out of range input`() {
        val w = window(start = -100, end = 2000)
        // start 钳到 0，end 钳到 1439：同日窗口 [0,1439)
        assertTrue(ChatQuietHoursPolicy.shouldSuppress(w, 0))
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(w, 1439))
        // 负分钟钳到 0：窗口 [10,20) 不含 0，窗口 [0,20) 含 0
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(window(start = 10, end = 20), -5))
        assertTrue(ChatQuietHoursPolicy.shouldSuppress(window(start = 0, end = 20), -5))
    }

    @Test
    fun `full day window suppresses every minute except end`() {
        val w = window(start = 0, end = 1439)
        assertTrue(ChatQuietHoursPolicy.shouldSuppress(w, 0))
        assertTrue(ChatQuietHoursPolicy.shouldSuppress(w, 1438))
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(w, 1439))
    }

    @Test
    fun `clamped equal minutes never suppress`() {
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(window(start = 1500, end = 1500), 1439))
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(window(start = -5, end = -5), 0))
    }
}
