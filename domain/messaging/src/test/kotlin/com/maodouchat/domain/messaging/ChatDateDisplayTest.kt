package com.maodouchat.domain.messaging

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatDateDisplayTest {

    private fun utc(y: Int, m: Int, d: Int, h: Int = 12): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(y, m - 1, d, h, 0, 0)
        }

    // 两个 instant 都在 UTC 正午附近：平移到本地时区后日差不变，断言与 CI 时区无关。
    private fun noon(y: Int, m: Int, d: Int): Long = utc(y, m, d).timeInMillis

    private fun localMillis(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(y, m - 1, d, h, min, 0)
        }.timeInMillis

    // ─── isSameDay（随迁） ───

    @Test
    fun sameDayIsTrueRegardlessOfTimeOfDay() {
        assertTrue(ChatDateDisplay.isSameDay(utc(2026, 4, 10, 0), utc(2026, 4, 10, 23)))
        assertTrue(ChatDateDisplay.isSameDay(utc(2026, 4, 10, 12), utc(2026, 4, 10, 12)))
    }

    @Test
    fun differentDayInSameYearIsFalse() {
        assertFalse(ChatDateDisplay.isSameDay(utc(2026, 4, 10), utc(2026, 4, 11)))
        assertFalse(ChatDateDisplay.isSameDay(utc(2026, 4, 10), utc(2026, 5, 10)))
    }

    @Test
    fun sameMonthAndDayInDifferentYearIsFalse() {
        // 少了 YEAR 比较就会误判为同一天
        assertFalse(ChatDateDisplay.isSameDay(utc(2026, 4, 10), utc(2025, 4, 10)))
    }

    @Test
    fun newYearEveAndNewYearDayAreDifferentDays() {
        assertFalse(ChatDateDisplay.isSameDay(utc(2025, 12, 31), utc(2026, 1, 1)))
    }

    // ─── isYesterday（随迁） ───

    @Test
    fun yesterdayIsTrue() {
        assertTrue(ChatDateDisplay.isYesterday(utc(2026, 4, 10), utc(2026, 4, 9)))
    }

    @Test
    fun todayAndTomorrowAreNotYesterday() {
        assertFalse(ChatDateDisplay.isYesterday(utc(2026, 4, 10), utc(2026, 4, 10)))
        assertFalse(ChatDateDisplay.isYesterday(utc(2026, 4, 10), utc(2026, 4, 11)))
    }

    @Test
    fun dayBeforeYesterdayIsNotYesterday() {
        assertFalse(ChatDateDisplay.isYesterday(utc(2026, 4, 10), utc(2026, 4, 8)))
    }

    @Test
    fun acrossTheYearBoundaryYesterdayStillWorks() {
        // 1/1 的昨天是 12/31
        assertTrue(ChatDateDisplay.isYesterday(utc(2026, 1, 1), utc(2025, 12, 31)))
        // 反方向：12/31 的昨天不是 1/1
        assertFalse(ChatDateDisplay.isYesterday(utc(2025, 12, 31), utc(2026, 1, 1)))
    }

    @Test
    fun leapDayIsHandledByCalendarArithmetic() {
        // 2024 是闰年：3/1 的昨天是 2/29
        assertTrue(ChatDateDisplay.isYesterday(utc(2024, 3, 1), utc(2024, 2, 29)))
        // 2026 不是闰年：3/1 的昨天是 2/28
        assertTrue(ChatDateDisplay.isYesterday(utc(2026, 3, 1), utc(2026, 2, 28)))
    }

    // ─── dateLabelBucket（新增） ───

    @Test
    fun bucketIsTodayForTheSameDay() {
        assertEquals(ChatDateLabel.Today, ChatDateDisplay.dateLabelBucket(noon(2026, 4, 10), noon(2026, 4, 10)))
    }

    @Test
    fun bucketIsYesterdayForThePreviousDay() {
        assertEquals(ChatDateLabel.Yesterday, ChatDateDisplay.dateLabelBucket(noon(2026, 4, 9), noon(2026, 4, 10)))
    }

    @Test
    fun bucketIsDateForOlderDays() {
        assertEquals(ChatDateLabel.Date, ChatDateDisplay.dateLabelBucket(noon(2026, 4, 8), noon(2026, 4, 10)))
    }

    @Test
    fun bucketAcrossTheYearBoundary() {
        assertEquals(ChatDateLabel.Yesterday, ChatDateDisplay.dateLabelBucket(noon(2025, 12, 31), noon(2026, 1, 1)))
        assertEquals(ChatDateLabel.Date, ChatDateDisplay.dateLabelBucket(noon(2025, 12, 30), noon(2026, 1, 1)))
    }

    // ─── formatChatDateTime（新增） ───

    @Test
    fun usDateTimeContainsDateAndAfternoonTime() {
        val text = ChatDateDisplay.formatChatDateTime(localMillis(2026, 4, 10, 14, 30), Locale.US)
        assertTrue(text.contains("2026"))
        assertTrue(text.contains("2:30"))
        assertTrue(text.contains("PM"))
        // 同一入参两次调用结果一致（ThreadLocal 缓存不改变输出）
        assertEquals(text, ChatDateDisplay.formatChatDateTime(localMillis(2026, 4, 10, 14, 30), Locale.US))
    }

    @Test
    fun chinaDateTimeUsesChineseDateWords() {
        val text = ChatDateDisplay.formatChatDateTime(localMillis(2026, 4, 10, 14, 30), Locale.CHINA)
        assertTrue(text.contains("2026年4月10日"))
        // 小时制随 JDK 的 CLDR 数据而变：JDK 17 是 12 小时制（下午2:30），CI 的 JDK 21 是 24 小时制（14:30）；
        // 实现只是把平台格式化原样透出，两种都接受。
        assertTrue(text.contains("2:30") || text.contains("14:30"))
    }

    @Test
    fun midnightFormatsAsTwelveAmInUsLocale() {
        val text = ChatDateDisplay.formatChatDateTime(localMillis(2026, 4, 10, 0, 0), Locale.US)
        assertTrue(text.contains("12:00"))
        assertTrue(text.contains("AM"))
    }
}
