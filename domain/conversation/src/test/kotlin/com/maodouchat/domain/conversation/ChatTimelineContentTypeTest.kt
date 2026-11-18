package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatTimelineContentTypeTest {

    @Test
    fun `date separator maps to date_separator`() {
        assertEquals(
            "date_separator",
            ChatItem.DateSeparator("2026-11-16", "2026-11-16").timelineContentType()
        )
    }

    @Test
    fun `message maps to message type name`() {
        val text = ChatItem.Msg(Message("m1", "c1", "u1", "hi", MessageType.TEXT), true)
        assertEquals("message_TEXT", text.timelineContentType())
        val image = ChatItem.Msg(Message("m2", "c1", "u1", "pic", MessageType.IMAGE), true)
        assertEquals("message_IMAGE", image.timelineContentType())
    }

    @Test
    fun `unread separator maps to unread_separator`() {
        assertEquals("unread_separator", ChatItem.UnreadSeparator("m9").timelineContentType())
    }
}
