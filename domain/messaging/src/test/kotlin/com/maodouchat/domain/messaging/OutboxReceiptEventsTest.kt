package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.MessagingV2Event
import com.maodouchat.core.model.MessagingV2EventAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OutboxReceiptEventsTest {

    @Test
    fun `delivery receipt targets the message with delivered status`() {
        val event = deliveryReceiptEvent("msg-1")
        assertEquals(MessagingV2EventAction.DELIVERY_RECEIPT, event.action)
        assertEquals("msg-1", event.targetMessageId)
        assertEquals(MessageStatus.DELIVERED.name, event.status)
        assertNull(event.throughMessageId)
    }

    @Test
    fun `read receipt targets the watermark and carries it as through`() {
        val event = readReceiptEvent("msg-9")
        assertEquals(MessagingV2EventAction.READ_RECEIPT, event.action)
        assertEquals("msg-9", event.targetMessageId)
        assertEquals(MessageStatus.READ.name, event.status)
        assertEquals("msg-9", event.throughMessageId)
    }

    @Test
    fun `play receipt targets the message with played status`() {
        val event = playReceiptEvent("msg-2")
        assertEquals(MessagingV2EventAction.PLAY_RECEIPT, event.action)
        assertEquals("msg-2", event.targetMessageId)
        assertEquals("PLAYED", event.status)
        assertNull(event.throughMessageId)
    }
}
