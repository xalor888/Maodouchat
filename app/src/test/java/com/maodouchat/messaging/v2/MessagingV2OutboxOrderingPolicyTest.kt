package com.maodouchat.messaging.v2

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessagingV2OutboxOrderingPolicyTest {
    @Test
    fun `data and user events preserve conversation causality`() {
        assertTrue(MessagingV2OutboxOrderingPolicy.requiresConversationOrder("DATA"))
        assertTrue(MessagingV2OutboxOrderingPolicy.requiresConversationOrder("EVENT"))
        assertFalse(MessagingV2OutboxOrderingPolicy.mayBypassConversationOrder("EVENT"))
    }

    @Test
    fun `protocol controls and receipts may bypass blocked user data`() {
        listOf("SENDER_KEY", "KEY_REQUEST", "RECEIPT").forEach { kind ->
            assertTrue(MessagingV2OutboxOrderingPolicy.mayBypassConversationOrder(kind))
            assertFalse(MessagingV2OutboxOrderingPolicy.requiresConversationOrder(kind))
        }
    }
}
