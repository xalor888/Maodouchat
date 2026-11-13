package com.maodouchat.ui.screen.chatdetail

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
 * G148：`ChatDetailScreenHelpers.kt` 里剩余纯函数的测试。
 *
 * 日期助手（`isSameDay` / `isYesterday`）已随 ChatDateDisplay 迁入 `:domain:messaging`，
 * 对应测试在 `ChatDateDisplayTest`。
 */
class ChatDetailScreenHelpersTest {

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
