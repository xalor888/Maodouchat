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

    @Test
    fun `data is causal and never bypasses`() {
        assertTrue(MessagingV2OutboxOrderingPolicy.requiresConversationOrder("DATA"))
        assertFalse(MessagingV2OutboxOrderingPolicy.mayBypassConversationOrder("DATA"))
    }

    @Test
    fun `unknown and empty kinds need no order and bypass nothing`() {
        listOf("ACK", "DELIVERY", "").forEach { kind ->
            assertFalse(MessagingV2OutboxOrderingPolicy.requiresConversationOrder(kind))
            assertFalse(MessagingV2OutboxOrderingPolicy.mayBypassConversationOrder(kind))
        }
    }

    @Test
    fun `kind matching is exact and case sensitive`() {
        listOf("data", "event", "receipt").forEach { kind ->
            assertFalse(MessagingV2OutboxOrderingPolicy.requiresConversationOrder(kind))
            assertFalse(MessagingV2OutboxOrderingPolicy.mayBypassConversationOrder(kind))
        }
    }

    @Test
    fun `causal and bypass kinds are disjoint`() {
        listOf("DATA", "EVENT", "SENDER_KEY", "KEY_REQUEST", "RECEIPT").forEach { kind ->
            val causal = MessagingV2OutboxOrderingPolicy.requiresConversationOrder(kind)
            val bypass = MessagingV2OutboxOrderingPolicy.mayBypassConversationOrder(kind)
            assertTrue(causal != bypass, "$kind must sit on exactly one side")
        }
    }
}
