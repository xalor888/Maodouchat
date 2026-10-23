package com.maodouchat.ui.screen.chatdetail

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `ChatSearchModel.searchWindowStart`：ALL 不过滤（null）；TODAY 归一到当天零点；其余纯毫秒偏移。 */
class SearchWindowStartTest {

    private val now = 1_772_000_000_000L // 固定时刻，避免依赖当前时间

    @Test
    fun `all window has no lower bound`() {
        assertNull(searchWindowStart(ChatSearchWindow.ALL, now))
    }

    @Test
    fun `seven and thirty day windows are pure offsets`() {
        assertEquals(now - 7L * 24 * 60 * 60 * 1000, searchWindowStart(ChatSearchWindow.SEVEN_DAYS, now))
        assertEquals(now - 30L * 24 * 60 * 60 * 1000, searchWindowStart(ChatSearchWindow.THIRTY_DAYS, now))
    }

    @Test
    fun `today window snaps to midnight of the same day`() {
        val actual = searchWindowStart(ChatSearchWindow.TODAY, now)!!
        val expected = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(expected, actual)
    }

    @Test
    fun `today window is at or before now and within the same day`() {
        val start = searchWindowStart(ChatSearchWindow.TODAY, now)!!
        assertEquals(true, start <= now)
        // 同一天：用 Calendar 比 YEAR + DAY_OF_YEAR
        val a = Calendar.getInstance().apply { timeInMillis = start }
        val b = Calendar.getInstance().apply { timeInMillis = now }
        assertEquals(a.get(Calendar.YEAR), b.get(Calendar.YEAR))
        assertEquals(a.get(Calendar.DAY_OF_YEAR), b.get(Calendar.DAY_OF_YEAR))
        // 且时刻恰为 00:00:00.000
        assertEquals(0, a.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, a.get(Calendar.MINUTE))
        assertEquals(0, a.get(Calendar.SECOND))
        assertEquals(0, a.get(Calendar.MILLISECOND))
    }

    @Test
    fun `today window is narrower than the seven day window`() {
        val today = searchWindowStart(ChatSearchWindow.TODAY, now)!!
        val seven = searchWindowStart(ChatSearchWindow.SEVEN_DAYS, now)!!
        assertEquals(true, today > seven)
    }

    @Test
    fun `every window value is handled`() {
        assertEquals(4, ChatSearchWindow.entries.size)
        ChatSearchWindow.entries.forEach { window ->
            val start = searchWindowStart(window, now)
            if (window == ChatSearchWindow.ALL) assertNull(start)
            else assertEquals(true, start != null && start <= now)
        }
    }

    @Test
    fun `zero now still applies pure offsets`() {
        assertEquals(-7L * 24 * 60 * 60 * 1000, searchWindowStart(ChatSearchWindow.SEVEN_DAYS, 0))
        assertEquals(-30L * 24 * 60 * 60 * 1000, searchWindowStart(ChatSearchWindow.THIRTY_DAYS, 0))
    }

    @Test
    fun `today at exactly midnight returns now`() {
        val midnight = searchWindowStart(ChatSearchWindow.TODAY, now)!!
        assertEquals(midnight, searchWindowStart(ChatSearchWindow.TODAY, midnight))
    }

    @Test
    fun `windows are ordered by width`() {
        val today = searchWindowStart(ChatSearchWindow.TODAY, now)!!
        val seven = searchWindowStart(ChatSearchWindow.SEVEN_DAYS, now)!!
        val thirty = searchWindowStart(ChatSearchWindow.THIRTY_DAYS, now)!!
        assertEquals(true, today > seven)
        assertEquals(true, seven > thirty)
    }

    @Test
    fun `repeated calls are deterministic`() {
        ChatSearchWindow.entries.forEach { window ->
            assertEquals(searchWindowStart(window, now), searchWindowStart(window, now))
        }
    }
}
