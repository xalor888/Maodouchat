package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OutboxSenderKeyRequestTest {

    @Test
    fun `same inputs produce the same id`() {
        val first = senderKeyRequestMessageId("chat-1", "alice", 7L, "msg-1", "owner", 3)
        val second = senderKeyRequestMessageId("chat-1", "alice", 7L, "msg-1", "owner", 3)

        assertEquals(first, second)
        assertTrue(first.startsWith("kr_"))
    }

    @Test
    fun `each input changes the id`() {
        val base = senderKeyRequestMessageId("chat-1", "alice", 7L, "msg-1", "owner", 3)
        assertNotEquals(base, senderKeyRequestMessageId("chat-2", "alice", 7L, "msg-1", "owner", 3))
        assertNotEquals(base, senderKeyRequestMessageId("chat-1", "bob", 7L, "msg-1", "owner", 3))
        assertNotEquals(base, senderKeyRequestMessageId("chat-1", "alice", 8L, "msg-1", "owner", 3))
        assertNotEquals(base, senderKeyRequestMessageId("chat-1", "alice", 7L, "msg-2", "owner", 3))
        assertNotEquals(base, senderKeyRequestMessageId("chat-1", "alice", 7L, "msg-1", "other", 3))
        assertNotEquals(base, senderKeyRequestMessageId("chat-1", "alice", 7L, "msg-1", "owner", 4))
    }

    @Test
    fun `wire constants match the v2 protocol`() {
        assertEquals("SENDER_KEY_REQUEST", TYPE_SENDER_KEY_REQUEST)
        assertEquals("requestedSenderUserId", ATTRIBUTE_REQUESTED_SENDER)
        assertEquals("failedMessageId", ATTRIBUTE_FAILED_MESSAGE_ID)
    }
}
