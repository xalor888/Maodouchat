package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingV2ModelPoliciesTest {

    @Test
    fun `outbox ordering keeps data and event causal`() {
        assertTrue(MessagingV2OutboxOrderingPolicy.requiresConversationOrder("DATA"))
        assertTrue(MessagingV2OutboxOrderingPolicy.requiresConversationOrder("EVENT"))
        assertFalse(MessagingV2OutboxOrderingPolicy.mayBypassConversationOrder("DATA"))
    }

    @Test
    fun `outbox ordering lets sender key and receipts bypass`() {
        assertTrue(MessagingV2OutboxOrderingPolicy.mayBypassConversationOrder("SENDER_KEY"))
        assertTrue(MessagingV2OutboxOrderingPolicy.mayBypassConversationOrder("RECEIPT"))
        assertFalse(MessagingV2OutboxOrderingPolicy.requiresConversationOrder("RECEIPT"))
    }

    @Test
    fun `mutation ticket carries kind and generation`() {
        val ticket = MessageMutationTicket("m1", MessageMutationKind.REVOKE, 7L)
        assertEquals("m1", ticket.messageId)
        assertEquals(MessageMutationKind.REVOKE, ticket.kind)
        assertEquals(7L, ticket.generation)
    }

    @Test
    fun `arrival decision carries unread delta and flags`() {
        val decision = MessagingV2ArrivalDecision(2, true, false)
        assertEquals(2, decision.unreadDelta)
        assertTrue(decision.shouldAttemptNotification)
        assertFalse(decision.shouldSendDeliveryReceipt)
    }
}
