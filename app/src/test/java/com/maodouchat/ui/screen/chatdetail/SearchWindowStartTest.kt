package com.maodouchat.ui.screen.chatdetail

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * G153：`ChatSearchModel.searchWindowStart` 的测试。
 *
 * ALL 不过滤（null）；TODAY 归一到当天 00:00:00.000；SEVEN/THIRTY_用毫秒偏移。
 * 偏移部分与时区无关，直接断言；TODAY 的「当天零点」用同一 Calendar 算期望值，
 * 避免把时区写死。
 */
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
}
