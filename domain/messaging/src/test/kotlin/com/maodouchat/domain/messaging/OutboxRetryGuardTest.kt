package com.maodouchat.domain.messaging

import com.maodouchat.domain.messaging.OutboxRetryGuard.Decision
import kotlin.test.Test
import kotlin.test.assertEquals

class OutboxRetryGuardTest {

    @Test
    fun `no existing row enqueues`() {
        assertEquals(
            Decision.ENQUEUE,
            OutboxRetryGuard.decide(null, null, "chat-1"),
        )
    }

    @Test
    fun `conversation mismatch errors`() {
        assertEquals(
            Decision.CONVERSATION_MISMATCH,
            OutboxRetryGuard.decide("chat-1", "DATA", "chat-2"),
        )
    }

    @Test
    fun `non data kind errors`() {
        assertEquals(
            Decision.KIND_MISMATCH,
            OutboxRetryGuard.decide("chat-1", "EVENT", "chat-1"),
        )
    }

    @Test
    fun `matching data row retries`() {
        assertEquals(
            Decision.RETRY,
            OutboxRetryGuard.decide("chat-1", "DATA", "chat-1"),
        )
    }
}
