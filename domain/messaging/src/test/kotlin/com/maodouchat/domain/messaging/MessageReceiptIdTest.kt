package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MessageReceiptIdTest {

    @Test
    fun `same inputs produce the same id`() {
        val first = receiptMessageId("DELIVERY_RECEIPT", "msg-1", "owner", 3)
        val second = receiptMessageId("DELIVERY_RECEIPT", "msg-1", "owner", 3)

        assertEquals(first, second)
        assertTrue(first.startsWith("r_"))
    }

    @Test
    fun `device id changes the id`() {
        assertNotEquals(
            receiptMessageId("DELIVERY_RECEIPT", "msg-1", "owner", 3),
            receiptMessageId("DELIVERY_RECEIPT", "msg-1", "owner", 4),
        )
    }

    @Test
    fun `action target and owner each change the id`() {
        val base = receiptMessageId("DELIVERY_RECEIPT", "msg-1", "owner", 3)
        assertNotEquals(base, receiptMessageId("READ_RECEIPT", "msg-1", "owner", 3))
        assertNotEquals(base, receiptMessageId("DELIVERY_RECEIPT", "msg-2", "owner", 3))
        assertNotEquals(base, receiptMessageId("DELIVERY_RECEIPT", "msg-1", "other", 3))
    }
}
