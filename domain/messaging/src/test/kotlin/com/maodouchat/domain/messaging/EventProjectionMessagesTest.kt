package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.MessagingV2Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventProjectionMessagesTest {

    private fun sampleMessage() = Message(
        id = "m1",
        chatId = "c1",
        senderId = "alice",
        content = "hello",
        timestamp = 1000L,
    )

    private fun event(content: String?, editedAt: Long?) = MessagingV2Event(
        action = "EDIT",
        targetMessageId = "m1",
        content = content,
        editedAt = editedAt,
    )

    @Test
    fun `edited message takes new content and event edited time`() {
        val edited = editedMessage(sampleMessage(), event("hello edited", 2000L), 3000L)

        assertEquals("hello edited", edited?.content)
        assertEquals(2000L, edited?.editedAt)
        assertEquals("m1", edited?.id)
    }

    @Test
    fun `edited message falls back to server timestamp without edited time`() {
        val edited = editedMessage(sampleMessage(), event("hello edited", null), 3000L)

        assertEquals(3000L, edited?.editedAt)
    }

    @Test
    fun `edited message without content is dropped`() {
        assertNull(editedMessage(sampleMessage(), event(null, 2000L), 3000L))
    }

    @Test
    fun `revoked message keeps explicit content and edited time`() {
        val revoked = revokedMessage(sampleMessage(), event("gone", 2000L), 3000L)

        assertEquals(MessageType.REVOKED, revoked.type)
        assertEquals("gone", revoked.content)
        assertEquals(2000L, revoked.editedAt)
    }

    @Test
    fun `revoked message falls back to default text and server timestamp`() {
        val revoked = revokedMessage(sampleMessage(), event(null, null), 3000L)

        assertEquals(MessageType.REVOKED, revoked.type)
        assertEquals("Message revoked", revoked.content)
        assertEquals(3000L, revoked.editedAt)
        assertEquals("c1", revoked.chatId)
        assertEquals("alice", revoked.senderId)
    }

    @Test
    fun `read receipt status is delivered in groups and read in direct chats`() {
        assertEquals(MessageStatus.DELIVERED, readReceiptStatus(true))
        assertEquals(MessageStatus.READ, readReceiptStatus(false))
    }
}
