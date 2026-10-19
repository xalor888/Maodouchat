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

    @Test
    fun `blank owner suppresses all user-visible effects`() {
        val decision = evaluate(ownerUserId = "")

        assertEquals(0, decision.unreadDelta)
        assertFalse(decision.shouldAttemptNotification)
        assertFalse(decision.shouldSendDeliveryReceipt)
    }

    @Test
    fun `own sent message has no user-visible effects`() {
        val decision = evaluate(senderUserId = "alice")

        assertEquals(0, decision.unreadDelta)
        assertFalse(decision.shouldAttemptNotification)
        assertFalse(decision.shouldSendDeliveryReceipt)
    }

    @Test
    fun `sender key distribution message is invisible`() {
        val decision = evaluate(messageType = MessageType.SK_DIST)

        assertEquals(0, decision.unreadDelta)
        assertFalse(decision.shouldAttemptNotification)
        assertFalse(decision.shouldSendDeliveryReceipt)
    }

    @Test
    fun `foreground arrival increments unread but does not notify`() {
        val decision = evaluate(appInForeground = true)

        assertEquals(1, decision.unreadDelta)
        assertFalse(decision.shouldAttemptNotification)
        assertTrue(decision.shouldSendDeliveryReceipt)
    }

    @Test
    fun `open chat detail suppresses the notification but keeps the receipt`() {
        val decision = evaluate(openChatDetailId = CHAT_ID)

        assertEquals(1, decision.unreadDelta)
        assertFalse(decision.shouldAttemptNotification)
        assertTrue(decision.shouldSendDeliveryReceipt)
    }

    private fun evaluate(
        isNew: Boolean = true,
        envelopeKind: String = "DATA",
        isConversationOccupied: Boolean = false,
        activeChatId: String? = null,
        ownerUserId: String = "alice",
        senderUserId: String = "bob",
        messageType: MessageType = MessageType.TEXT,
        appInForeground: Boolean = false,
        openChatDetailId: String? = null,
    ) = MessagingV2ArrivalPolicy.evaluate(
        isNew = isNew,
        ownerUserId = ownerUserId,
        senderUserId = senderUserId,
        conversationId = CHAT_ID,
        messageType = messageType,
        envelopeKind = envelopeKind,
        isConversationOccupied = isConversationOccupied,
        appInForeground = appInForeground,
        activeChatId = activeChatId,
        openChatDetailId = openChatDetailId,
    )

    private companion object {
        const val CHAT_ID = "group-1"
    }
}
