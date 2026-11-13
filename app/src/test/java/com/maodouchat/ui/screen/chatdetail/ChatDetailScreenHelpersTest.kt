package com.maodouchat.ui.screen.chatdetail

import java.util.Calendar
import java.util.TimeZone
import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.User
import com.maodouchat.core.model.truncatedSenderId
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G148：`ChatDetailScreenHelpers.kt` 里三个纯函数的测试。
 *
 * 日期助手最容易踩的是**跨年**：`isSameDay` 同时比 YEAR 与 DAY_OF_YEAR，
 * 少比任何一项都会让 12/31 与 1/1 判成同一天；`isYesterday` 用
 * 「clone + DAY_OF_YEAR - 1」而不是「毫秒减 86400000」，因为后者会被夏令时坑。
 */
class ChatDetailScreenHelpersTest {

    private fun utc(y: Int, m: Int, d: Int, h: Int = 12): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(y, m - 1, d, h, 0, 0)
        }

    // ─── truncatedSenderId ───

    @Test
    fun `truncated sender id returns null for blank`() {
        assertNull(truncatedSenderId(""))
        assertNull(truncatedSenderId("   "))
        assertNull(truncatedSenderId("\t\n"))
    }

    @Test
    fun `truncated sender id keeps short ids verbatim after trimming`() {
        assertEquals("abc", truncatedSenderId("abc"))
        assertEquals("abc", truncatedSenderId("  abc  "))
        assertEquals("12345678", truncatedSenderId("12345678"))
    }

    @Test
    fun `truncated sender id cuts long ids to eight chars`() {
        assertEquals("12345678", truncatedSenderId("123456789"))
        assertEquals("abcdefgh", truncatedSenderId("abcdefghijklmnop"))
        // 先 trim 再截断
        assertEquals("12345678", truncatedSenderId("  123456789  "))
    }

    // ─── isSameDay ───

    @Test
    fun `same day is true regardless of time of day`() {
        assertTrue(isSameDay(utc(2026, 4, 10, 0), utc(2026, 4, 10, 23)))
        assertTrue(isSameDay(utc(2026, 4, 10, 12), utc(2026, 4, 10, 12)))
    }

    @Test
    fun `different day in same year is false`() {
        assertFalse(isSameDay(utc(2026, 4, 10), utc(2026, 4, 11)))
        assertFalse(isSameDay(utc(2026, 4, 10), utc(2026, 5, 10)))
    }

    @Test
    fun `same month and day in a different year is false`() {
        // 少了 YEAR 比较就会误判为同一天
        assertFalse(isSameDay(utc(2026, 4, 10), utc(2025, 4, 10)))
    }

    @Test
    fun `new year eve and new year day are different days`() {
        assertFalse(isSameDay(utc(2025, 12, 31), utc(2026, 1, 1)))
    }

    // ─── isYesterday ───

    @Test
    fun `yesterday is true`() {
        assertTrue(isYesterday(utc(2026, 4, 10), utc(2026, 4, 9)))
    }

    @Test
    fun `today and tomorrow are not yesterday`() {
        assertFalse(isYesterday(utc(2026, 4, 10), utc(2026, 4, 10)))
        assertFalse(isYesterday(utc(2026, 4, 10), utc(2026, 4, 11)))
    }

    @Test
    fun `day before yesterday is not yesterday`() {
        assertFalse(isYesterday(utc(2026, 4, 10), utc(2026, 4, 8)))
    }

    @Test
    fun `across the year boundary yesterday still works`() {
        // 1/1 的昨天是 12/31
        assertTrue(isYesterday(utc(2026, 1, 1), utc(2025, 12, 31)))
        // 反方向：12/31 的昨天不是 1/1
        assertFalse(isYesterday(utc(2025, 12, 31), utc(2026, 1, 1)))
    }

    @Test
    fun `leap day is handled by calendar arithmetic`() {
        // 2024 是闰年：3/1 的昨天是 2/29
        assertTrue(isYesterday(utc(2024, 3, 1), utc(2024, 2, 29)))
        // 2026 不是闰年：3/1 的昨天是 2/28
        assertTrue(isYesterday(utc(2026, 3, 1), utc(2026, 2, 28)))
    }

    // ─── forwardTargetName ───

    private fun stubForwardContext(): android.content.Context {
        val res = mockk<android.content.res.Resources>(relaxed = true)
        every { res.getQuantityString(any(), any(), any()) } answers
            { "group(${args[1]})" }
        return mockk<android.content.Context>(relaxed = true).also { ctx ->
            every { ctx.resources } returns res
            every { ctx.getString(any()) } returns "private"
        }
    }

    @Test
    fun `group forward target uses the group name when present`() {
        val chat = Chat(id = "g", isGroup = true, groupName = "Dev Team", participants = emptyList())
        assertEquals("Dev Team", forwardTargetName(stubForwardContext(), chat, "me"))
    }

    @Test
    fun `group forward target falls back to the member count`() {
        val chat = Chat(id = "g", isGroup = true, groupName = null, participants = List(3) { User(id = "u$it", name = "u$it") })
        assertEquals("group(3)", forwardTargetName(stubForwardContext(), chat, "me"))
    }

    @Test
    fun `direct forward target picks the peer`() {
        val chat = Chat(
            id = "d",
            isGroup = false,
            participants = listOf(User(id = "me", name = "Me"), User(id = "peer", name = "Alice"))
        )
        assertEquals("Alice", forwardTargetName(stubForwardContext(), chat, "me"))
    }

    @Test
    fun `direct forward target falls back to the private label`() {
        // 只有自己——找不到对方，回落 chat_private
        val chat = Chat(id = "d", isGroup = false, participants = listOf(User(id = "me", name = "Me")))
        assertEquals("private", forwardTargetName(stubForwardContext(), chat, "me"))
    }
}
