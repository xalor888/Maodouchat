package com.maodouchat.messaging.v2

import com.maodouchat.data.model.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessagingV2ArrivalPolicyTest {
    @Test
    fun `duplicate inbox message has no repeated user-visible effects`() {
        val decision = evaluate(isNew = false)

        assertEquals(0, decision.unreadDelta)
        assertFalse(decision.shouldAttemptNotification)
        assertFalse(decision.shouldSendDeliveryReceipt)
    }

    @Test
    fun `currently open conversation does not increment unread or notify`() {
        val decision = evaluate(
            isConversationOccupied = true,
            activeChatId = CHAT_ID,
        )

        assertEquals(0, decision.unreadDelta)
        assertFalse(decision.shouldAttemptNotification)
        assertTrue(decision.shouldSendDeliveryReceipt)
    }

    @Test
    fun `offline group arrival increments unread and attempts one notification`() {
        val decision = evaluate()

        assertEquals(1, decision.unreadDelta)
        assertTrue(decision.shouldAttemptNotification)
        assertTrue(decision.shouldSendDeliveryReceipt)
    }

    @Test
    fun `service message never emits a delivery receipt`() {
        val decision = evaluate(envelopeKind = "SERVICE")

        assertEquals(1, decision.unreadDelta)
        assertTrue(decision.shouldAttemptNotification)
        assertFalse(decision.shouldSendDeliveryReceipt)
    }

    private fun evaluate(
        isNew: Boolean = true,
        envelopeKind: String = "DATA",
        isConversationOccupied: Boolean = false,
        activeChatId: String? = null,
    ) = MessagingV2ArrivalPolicy.evaluate(
        isNew = isNew,
        ownerUserId = "alice",
        senderUserId = "bob",
        conversationId = CHAT_ID,
        messageType = MessageType.TEXT,
        envelopeKind = envelopeKind,
        isConversationOccupied = isConversationOccupied,
        appInForeground = false,
        activeChatId = activeChatId,
        openChatDetailId = null,
    )

    private companion object {
        const val CHAT_ID = "group-1"
    }
}
