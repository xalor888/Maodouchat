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

    @Test
    fun `unread separator falls back before oldest message when id not in window`() {
        val items = buildChatItems(
            listOf(
                message("m1", "u1", 1L),
                message("m2", "u1", 2L)
            ),
            labelForTimestamp = { "day-1" },
            unreadSeparatorId = "missing"
        )

        assertTrue(items[0] is ChatItem.DateSeparator)
        val separator = items[1] as ChatItem.UnreadSeparator
        assertEquals("unread_m1", separator.listKey)
    }

    @Test
    fun `unread separator is inserted right before its message`() {
        val items = buildChatItems(
            listOf(
                message("m1", "u1", 1L),
                message("m2", "u1", 2L)
            ),
            labelForTimestamp = { "day-1" },
            unreadSeparatorId = "m2"
        )

        assertEquals(2, items.indexOfFirst { it is ChatItem.UnreadSeparator })
        assertEquals("unread_m2", (items[2] as ChatItem.UnreadSeparator).listKey)
        assertEquals(listOf("m1", "m2"), items.filterIsInstance<ChatItem.Msg>().map { it.message.id })
    }

    @Test
    fun `equal timestamps fall back to id ordering`() {
        val items = buildChatItems(
            listOf(
                message("b", "u1", 5L),
                message("a", "u1", 5L)
            ),
            labelForTimestamp = { "day-1" }
        )

        assertEquals(listOf("a", "b"), items.filterIsInstance<ChatItem.Msg>().map { it.message.id })
    }

    @Test
    fun `all sk dist messages yield an empty timeline`() {
        val items = buildChatItems(
            listOf(
                message("s1", "u1", 1L, MessageType.SK_DIST),
                message("s2", "u2", 2L, MessageType.SK_DIST)
            ),
            labelForTimestamp = { "day-1" }
        )

        assertTrue(items.isEmpty())
    }

    @Test
    fun `group edge is true only on the last message of each sender group`() {
        val items = buildChatItems(
            listOf(
                message("m1", "u1", 1L),
                message("m2", "u1", 2L),
                message("m3", "u2", 3L)
            ),
            labelForTimestamp = { "day-1" }
        )
        val rows = items.filterIsInstance<ChatItem.Msg>()

        assertFalse(rows[0].isGroupEdge)
        assertTrue(rows[1].isGroupEdge)
        assertTrue(rows[2].isGroupEdge)
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

    @Test
    fun `exactly six minutes apart starts a new avatar group`() {
        // sameSenderGroup uses a strict `<` window, so a 360000ms gap breaks the group.
        val items = buildChatItems(
            listOf(
                message("m1", "u1", 1L),
                message("m2", "u1", 360001L)
            ),
            labelForTimestamp = { "day-1" }
        )
        val rows = items.filterIsInstance<ChatItem.Msg>()
        assertTrue(rows[1].showAvatar)
        assertTrue(rows[1].showSenderName)
    }

    @Test
    fun `just under six minutes stays in the same avatar group`() {
        val items = buildChatItems(
            listOf(
                message("m1", "u1", 1L),
                message("m2", "u1", 360000L)
            ),
            labelForTimestamp = { "day-1" }
        )
        val rows = items.filterIsInstance<ChatItem.Msg>()
        assertFalse(rows[1].showAvatar)
        assertFalse(rows[1].showSenderName)
    }

    @Test
    fun `blank id messages each get a distinct list key`() {
        val items = buildChatItems(
            listOf(
                message("", "u1", 1L),
                message("", "u2", 2L)
            ),
            labelForTimestamp = { "day-1" }
        )
        val rows = items.filterIsInstance<ChatItem.Msg>()
        assertEquals(listOf("msg_blank_1", "msg_blank_2"), rows.map { it.listKey })
    }

    @Test
    fun `missing unread id with blank first message falls back to window key`() {
        val items = buildChatItems(
            listOf(
                message("", "u1", 1L),
                message("m2", "u1", 2L)
            ),
            labelForTimestamp = { "day-1" },
            unreadSeparatorId = "missing"
        )
        assertTrue(items[1] is ChatItem.UnreadSeparator)
        assertEquals("unread_window", (items[1] as ChatItem.UnreadSeparator).listKey)
    }

    @Test
    fun `equal timestamps across senders still order by id`() {
        val items = buildChatItems(
            listOf(
                message("b", "u2", 5L),
                message("a", "u1", 5L)
            ),
            labelForTimestamp = { "day-1" }
        )
        val rows = items.filterIsInstance<ChatItem.Msg>()
        assertEquals(listOf("a", "b"), rows.map { it.message.id })
        assertTrue(rows[0].showAvatar)
        assertTrue(rows[1].showAvatar)
    }

    @Test
    fun `empty message list yields an empty timeline`() {
        val items = buildChatItems(emptyList(), labelForTimestamp = { "day-1" })

        assertTrue(items.isEmpty())
    }

    @Test
    fun `recurring date label keeps distinct separator keys`() {
        val items = buildChatItems(
            listOf(
                message("m1", "u1", 1L),
                message("m2", "u1", 2L),
                message("m3", "u1", 3L)
            ),
            labelForTimestamp = { if (it == 2L) "day-2" else "day-1" }
        )
        val separators = items.filterIsInstance<ChatItem.DateSeparator>()

        assertEquals(listOf("day-1", "day-2", "day-1"), separators.map { it.label })
        assertEquals(3, separators.map { it.listKey }.toSet().size)
    }

    @Test
    fun `unread separator is inserted once even when its id repeats`() {
        val items = buildChatItems(
            listOf(
                message("m1", "u1", 1L),
                message("m1", "u1", 2L),
                message("m2", "u1", 3L)
            ),
            labelForTimestamp = { "day-1" },
            unreadSeparatorId = "m1"
        )

        assertEquals(1, items.filterIsInstance<ChatItem.UnreadSeparator>().size)
        assertEquals(listOf("m1", "m2"), items.filterIsInstance<ChatItem.Msg>().map { it.message.id })
    }

    @Test
    fun `same sender crossing a day boundary starts a new avatar group`() {
        val items = buildChatItems(
            listOf(
                message("m1", "u1", 1L),
                message("m2", "u1", 2L)
            ),
            labelForTimestamp = { if (it == 1L) "day-1" else "day-2" }
        )
        val rows = items.filterIsInstance<ChatItem.Msg>()

        assertTrue(rows[0].showAvatar)
        assertTrue(rows[1].showAvatar)
        assertTrue(rows[1].showSenderName)
    }

    @Test
    fun `middle message of a three message group is not a group edge`() {
        val items = buildChatItems(
            listOf(
                message("m1", "u1", 1L),
                message("m2", "u1", 2L),
                message("m3", "u1", 3L)
            ),
            labelForTimestamp = { "day-1" }
        )
        val rows = items.filterIsInstance<ChatItem.Msg>()

        assertFalse(rows[0].isGroupEdge)
        assertFalse(rows[1].isGroupEdge)
        assertTrue(rows[2].isGroupEdge)
    }
}
