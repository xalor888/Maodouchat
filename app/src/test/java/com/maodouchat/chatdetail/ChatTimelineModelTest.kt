package com.maodouchat.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.screen.chatdetail.ChatItem
import com.maodouchat.ui.screen.chatdetail.buildChatItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTimelineModelTest {
    @Test
    fun `timeline sorts deterministically and resolves each date label once`() {
        var labelCalls = 0
        val items = buildChatItems(
            messages = listOf(
                message("m3", "u1", 2_001L),
                message("m2", "u2", 1_002L),
                message("m1", "u1", 1_001L)
            ),
            labelForTimestamp = { timestamp ->
                labelCalls++
                if (timestamp < 2_000L) "day-1" else "day-2"
            }
        )

        assertEquals(3, labelCalls)
        assertEquals(listOf("m1", "m2", "m3"), items.filterIsInstance<ChatItem.Msg>().map { it.message.id })
        assertEquals(listOf("day-1", "day-2"), items.filterIsInstance<ChatItem.DateSeparator>().map { it.label })
    }

    @Test
    fun `avatar and sender name show together at start of date or sender run`() {
        val items = buildChatItems(
            listOf(
                message("m1", "u1", 1L),
                message("m2", "u1", 2L),
                message("m3", "u2", 3L),
                message("m4", "u2", 2_000L)
            ),
            labelForTimestamp = { if (it < 1_000L) "day-1" else "day-2" }
        )
        val rows = items.filterIsInstance<ChatItem.Msg>()

        // 头像和名字都在组首：m1/m2 同为 u1 day-1，只在 m1；m3 是 u2 day-1 组首；m4 跨天另起一组。
        assertTrue(rows[0].showAvatar)
        assertTrue(rows[0].showSenderName)
        assertFalse(rows[1].showAvatar)
        assertFalse(rows[1].showSenderName)
        assertTrue(rows[2].showAvatar)
        assertTrue(rows[2].showSenderName)
        assertTrue(rows[3].showAvatar)
        assertTrue(rows[3].showSenderName)
    }

    @Test
    fun `same sender more than six minutes apart starts a new avatar group`() {
        val sixMin = 6L * 60L * 1000L
        val items = buildChatItems(
            listOf(
                message("m1", "u1", 1L),
                message("m2", "u1", sixMin + 2L),
            ),
            labelForTimestamp = { "day-1" }
        )
        val rows = items.filterIsInstance<ChatItem.Msg>()
        assertTrue(rows[0].showAvatar)
        assertTrue(rows[0].showSenderName)
        assertTrue(rows[1].showAvatar)
        assertTrue(rows[1].showSenderName)
    }

    @Test
    fun `sk dist rows stay out of timeline but do not break sender runs`() {
        val items = buildChatItems(
            listOf(
                message("m1", "u1", 1L),
                message("sk", "u1", 2L, MessageType.SK_DIST),
                message("m2", "u1", 3L)
            ),
            labelForTimestamp = { "day-1" }
        )
        val rows = items.filterIsInstance<ChatItem.Msg>()
        assertEquals(listOf("m1", "m2"), rows.map { it.message.id })
        // 头像和名字都在组首；SK_DIST 不打断发送者分组
        assertTrue(rows[0].showAvatar)
        assertTrue(rows[0].showSenderName)
        assertFalse(rows[1].showAvatar)
        assertFalse(rows[1].showSenderName)
    }

    @Test
    fun `duplicate message ids keep first occurrence and unique list keys`() {
        val items = buildChatItems(
            listOf(
                message("m1", "u1", 1L),
                message("m1", "u1", 2L),
                message("m2", "u2", 3L)
            ),
            labelForTimestamp = { "day-1" }
        )
        val rows = items.filterIsInstance<ChatItem.Msg>()
        assertEquals(listOf("m1", "m2"), rows.map { it.message.id })
        assertEquals(rows.size, rows.map { it.listKey }.toSet().size)
        assertTrue(items.map { it.listKey }.toSet().size == items.size)
        assertTrue(items.all { it.listKey.startsWith("date_") || it.listKey.startsWith("msg_") || it.listKey.startsWith("unread_") })
    }

    @Test
    fun `blank ids and unread separator never collide with message keys`() {
        val items = buildChatItems(
            listOf(
                message("", "u1", 1L),
                message("", "u2", 2L),
                message("m3", "u3", 3L)
            ),
            labelForTimestamp = { "day-1" },
            unreadSeparatorId = "missing"
        )
        val keys = items.map { it.listKey }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(items.any { it is ChatItem.UnreadSeparator })
        assertEquals(2, items.filterIsInstance<ChatItem.Msg>().count { it.message.id.isBlank() })
    }

    private fun message(
        id: String,
        senderId: String,
        timestamp: Long,
        type: MessageType = MessageType.TEXT
    ) = Message(
        id = id,
        chatId = "chat",
        senderId = senderId,
        content = id,
        type = type,
        timestamp = timestamp,
        status = MessageStatus.SENT
    )
}
