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

    @Test
    fun `whitespace-padded kinds are treated as unknown`() {
        listOf(" DATA", "DATA ", "\tDATA", "RECEIPT ", "\nEVENT").forEach { kind ->
            assertFalse(MessagingV2OutboxOrderingPolicy.requiresConversationOrder(kind))
            assertFalse(MessagingV2OutboxOrderingPolicy.mayBypassConversationOrder(kind))
        }
    }

    @Test
    fun `only the documented causal kinds require conversation order`() {
        listOf("SENDER_KEY", "KEY_REQUEST", "RECEIPT", "ACK", "DELIVERY", "MESSAGE", "OUTBOX").forEach { kind ->
            assertFalse(MessagingV2OutboxOrderingPolicy.requiresConversationOrder(kind))
        }
    }

    @Test
    fun `only the documented kinds may bypass conversation order`() {
        listOf("DATA", "EVENT", "ACK", "DELIVERY", "SENDERKEY", "KEYREQUEST", "SENDER-KEY").forEach { kind ->
            assertFalse(MessagingV2OutboxOrderingPolicy.mayBypassConversationOrder(kind))
        }
    }

    @Test
    fun `truncated kind names are unknown`() {
        listOf("DAT", "EVEN", "SENDER_KE", "R", "D").forEach { kind ->
            assertFalse(MessagingV2OutboxOrderingPolicy.requiresConversationOrder(kind))
            assertFalse(MessagingV2OutboxOrderingPolicy.mayBypassConversationOrder(kind))
        }
    }

    @Test
    fun `suffixed kind names are unknown`() {
        listOf("DATAX", "EVENTS", "SENDER_KEYS", "KEY_REQUESTS", "RECEIPTS").forEach { kind ->
            assertFalse(MessagingV2OutboxOrderingPolicy.requiresConversationOrder(kind))
            assertFalse(MessagingV2OutboxOrderingPolicy.mayBypassConversationOrder(kind))
        }
    }

    @Test
    fun `mixed-case kind names are unknown`() {
        listOf("Data", "Event", "Receipt", "Sender_Key", "Key_Request").forEach { kind ->
            assertFalse(MessagingV2OutboxOrderingPolicy.requiresConversationOrder(kind))
            assertFalse(MessagingV2OutboxOrderingPolicy.mayBypassConversationOrder(kind))
        }
    }

    @Test
    fun `separator variants of documented kinds are unknown`() {
        listOf("SENDER KEY", "KEY-REQUEST", "RECEIPT_", "_RECEIPT", "SENDER__KEY").forEach { kind ->
            assertFalse(MessagingV2OutboxOrderingPolicy.requiresConversationOrder(kind))
            assertFalse(MessagingV2OutboxOrderingPolicy.mayBypassConversationOrder(kind))
        }
    }

    @Test
    fun `kinds with inner whitespace are unknown`() {
        listOf("DA TA", "EV\tENT", "RECE\nIPT", "SENDER\tKEY").forEach { kind ->
            assertFalse(MessagingV2OutboxOrderingPolicy.requiresConversationOrder(kind))
            assertFalse(MessagingV2OutboxOrderingPolicy.mayBypassConversationOrder(kind))
        }
    }

    @Test
    fun `compound near-miss names are unknown`() {
        listOf("MESSAGE_DATA", "DATA_EVENT", "EVENT_DATA", "KEY", "SENDER", "REQUEST", "RECEIPTDATA", "DATARECEIPT").forEach { kind ->
            assertFalse(MessagingV2OutboxOrderingPolicy.requiresConversationOrder(kind))
            assertFalse(MessagingV2OutboxOrderingPolicy.mayBypassConversationOrder(kind))
        }
    }

    @Test
    fun `documented classification is stable across repeated evaluation`() {
        listOf("DATA", "EVENT", "SENDER_KEY", "KEY_REQUEST", "RECEIPT").forEach { kind ->
            val firstCausal = MessagingV2OutboxOrderingPolicy.requiresConversationOrder(kind)
            val firstBypass = MessagingV2OutboxOrderingPolicy.mayBypassConversationOrder(kind)
            assertTrue(MessagingV2OutboxOrderingPolicy.requiresConversationOrder(kind) == firstCausal)
            assertTrue(MessagingV2OutboxOrderingPolicy.mayBypassConversationOrder(kind) == firstBypass)
        }
        assertTrue(MessagingV2OutboxOrderingPolicy.requiresConversationOrder("DATA"))
        assertTrue(MessagingV2OutboxOrderingPolicy.requiresConversationOrder("EVENT"))
        assertTrue(MessagingV2OutboxOrderingPolicy.mayBypassConversationOrder("SENDER_KEY"))
        assertTrue(MessagingV2OutboxOrderingPolicy.mayBypassConversationOrder("KEY_REQUEST"))
        assertTrue(MessagingV2OutboxOrderingPolicy.mayBypassConversationOrder("RECEIPT"))
    }
}
