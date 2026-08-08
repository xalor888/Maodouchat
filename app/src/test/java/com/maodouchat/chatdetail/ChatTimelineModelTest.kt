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
    fun `avatar starts a date or sender run only`() {
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

        assertTrue(rows[0].showAvatar)
        assertFalse(rows[1].showAvatar)
        assertTrue(rows[2].showAvatar)
        assertTrue(rows[3].showAvatar)
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
        assertTrue(rows[0].showAvatar)
        // SK_DIST between same sender must not force a second avatar on m2.
        assertFalse(rows[1].showAvatar)
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
