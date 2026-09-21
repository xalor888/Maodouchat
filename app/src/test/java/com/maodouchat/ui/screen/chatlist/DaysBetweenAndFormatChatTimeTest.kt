package com.maodouchat.ui.screen.chatlist

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G157：`ChatListComponents` 里两个时间函数的测试。
 *
 * `daysBetween` 按**零点对齐**算自然日差——所以「同一天的不同时刻」差是 0，
 * 这一点是 `formatChatTime` 能正确显示「今天」的前提。
 * `formatChatTime` 的断言只用**形状**（正则/长度），不写死具体值，
 * 因为 HH:mm / EEE / MM-dd 都随区域与语言变化。
 */
class DaysBetweenAndFormatChatTimeTest {

    // daysBetween 内部用 Calendar.getInstance()（默认时区）做零点对齐，
    // 所以测试也必须用默认时区构造输入，否则「零点」不是同一个零点。
    private fun cal(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0): Calendar =
        Calendar.getInstance().apply {
            clear()
            set(y, m - 1, d, h, min, 0)
        }

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        cal(y, m, d, h, min).timeInMillis

    // ─── daysBetween ───

    @Test
    fun `same day is zero even at different times`() {
        assertEquals(0, daysBetween(cal(2026, 4, 10, 0), cal(2026, 4, 10, 23)))
        assertEquals(0, daysBetween(cal(2026, 4, 10, 23, 59), cal(2026, 4, 10, 0, 1)))
    }

    @Test
    fun `one to six days apart`() {
        for (d in 1..6) {
            assertEquals("差 $d 天", d, daysBetween(cal(2026, 4, 10), cal(2026, 4, 10 + d)))
        }
    }

    @Test
    fun `seven days and beyond`() {
        assertEquals(7, daysBetween(cal(2026, 4, 10), cal(2026, 4, 17)))
        assertEquals(30, daysBetween(cal(2026, 4, 10), cal(2026, 5, 10)))
    }

    @Test
    fun `across month and year boundaries`() {
        // 4/30 -> 5/1 是 1 天
        assertEquals(1, daysBetween(cal(2026, 4, 30), cal(2026, 5, 1)))
        // 12/31 -> 1/1 是 1 天
        assertEquals(1, daysBetween(cal(2025, 12, 31), cal(2026, 1, 1)))
        // 2026 全年（非闰年）
        assertEquals(365, daysBetween(cal(2026, 1, 1), cal(2027, 1, 1)))
        // 2024 是闰年：2/29 存在，2/28 -> 3/1 是 2 天
        assertEquals(2, daysBetween(cal(2024, 2, 28), cal(2024, 3, 1)))
    }

    @Test
    fun `midnight alignment means a late night message still counts as the same day`() {
        // 23:59 与次日 00:01 相差 1 天（零点对齐）
        assertEquals(1, daysBetween(cal(2026, 4, 10, 23, 59), cal(2026, 4, 11, 0, 1)))
    }

    // ─── formatChatTime ───

    @Test
    fun `non positive timestamp renders as empty`() {
        assertEquals("", formatChatTime(0L))
        assertEquals("", formatChatTime(-1L))
        assertEquals("", formatChatTime(Long.MIN_VALUE))
    }

    @Test
    fun `today renders as a clock time`() {
        val now = System.currentTimeMillis()
        val out = formatChatTime(now)
        // HH:mm 或 H:mm——两个数字、一个冒号、两个数字
        assertTrue("今天的消息应显示时刻，实际：$out", out.matches(Regex("\\d{1,2}:\\d{2}")))
    }

    @Test
    fun `within the last week renders as a weekday abbreviation`() {
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000
        (1..6).forEach { back ->
            val out = formatChatTime(now - back * day)
            // EEE 的本地化形态：不写死中英文，只要求非空、且不含冒号（排除被当成当天的可能）
            assertTrue("$back 天前应显示星期，实际：$out", out.isNotBlank())
            assertTrue("$back 天前不应显示时刻，实际：$out", !out.contains(":"))
            assertTrue("$back 天前不应显示日期，实际：$out", !out.contains("/"))
        }
    }

    @Test
    fun `a week or older renders as a date`() {
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000
        listOf(7, 8, 30, 400).forEach { back ->
            val out = formatChatTime(now - back * day)
            assertTrue("$back 天前应显示日期，实际：$out", out.contains("/"))
        }
    }

    @Test
    fun `a far past timestamp still renders as a date`() {
        // 2000-01-01 00:00 UTC
        val out = formatChatTime(cal(2000, 1, 1, 0, 0).timeInMillis)
        assertTrue("实际：$out", out.contains("/"))
    }

    @Test
    fun `the day difference is measured in the device time zone`() {
        // G158：上一轮我把「daysBetween 依赖默认时区」误判成隐患，其实是**正确语义**——
        // 聊天列表要按用户设备所在时区显示「今天 / 周一 / 日期」。
        // 这里把语义钉住：入参用任意时区构造，结果都按**默认时区**的自然日差算。
        //
        // 取 UTC+14 的 4/10 00:00 与 4/10 23:00——在 UTC+14 看是同一天，
        // 但在 UTC-5 之类的负偏移设备上是两天。设备时区才是准绳。
        val tz14 = TimeZone.getTimeZone("Pacific/Kiritimati")
        fun cal(d: Int, h: Int): Calendar =
            Calendar.getInstance(tz14).apply {
                clear()
                set(2026, 3, d, h, 0, 0)
            }
        val a = cal(10, 0)
        val b = cal(10, 23)

        // 用同一对 instant、但换成默认时区重新读取，应当得到同一个结果
        val expected = daysBetween(
            Calendar.getInstance().apply { timeInMillis = a.timeInMillis },
            Calendar.getInstance().apply { timeInMillis = b.timeInMillis },
        )
        assertEquals(
            "跨时区入参应与默认时区入参得到同一个自然日差",
            expected,
            daysBetween(a, b),
        )
        // 且这个结果就是默认时区下的真实日差（不写死 0 或 1，随运行环境的默认时区而定）
        assertTrue("结果应为非负", expected >= 0)
    }

    @Test
    fun `today is detected with the same zero alignment rule as daysBetween`() {
        // 与 daysBetween 的自洽性：当天消息的 daysBetween(now, msg) 必为 0
        val now = System.currentTimeMillis()
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val msg = Calendar.getInstance().apply { timeInMillis = now }
        assertEquals(0, daysBetween(msg, today))
    }
}
