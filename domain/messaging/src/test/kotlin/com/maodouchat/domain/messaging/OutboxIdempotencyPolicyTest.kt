package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutboxIdempotencyPolicyTest {

    @Test
    fun `same conversation kind and payload is a duplicate`() {
        assertTrue(
            OutboxIdempotencyPolicy.isSameOutbox(
                existingConversationId = "chat-1",
                existingKind = "DATA",
                existingPayload = "payload",
                conversationId = "chat-1",
                kind = "DATA",
                payload = "payload",
            ),
        )
    }

    @Test
    fun `different conversation is not a duplicate`() {
        assertFalse(
            OutboxIdempotencyPolicy.isSameOutbox(
                existingConversationId = "chat-1",
                existingKind = "DATA",
                existingPayload = "payload",
                conversationId = "chat-2",
                kind = "DATA",
                payload = "payload",
            ),
        )
    }

    @Test
    fun `different kind is not a duplicate`() {
        assertFalse(
            OutboxIdempotencyPolicy.isSameOutbox(
                existingConversationId = "chat-1",
                existingKind = "DATA",
                existingPayload = "payload",
                conversationId = "chat-1",
                kind = "EVENT",
                payload = "payload",
            ),
        )
    }

    @Test
    fun `different payload is not a duplicate`() {
        assertFalse(
            OutboxIdempotencyPolicy.isSameOutbox(
                existingConversationId = "chat-1",
                existingKind = "DATA",
                existingPayload = "payload",
                conversationId = "chat-1",
                kind = "DATA",
                payload = "other",
            ),
        )
    }
}
