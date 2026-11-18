package com.maodouchat.domain.conversation

import com.maodouchat.core.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReceiptTest {

    @Test
    fun receiptHoldsStatus() {
        val receipt = Receipt(fromMe = true, status = MessageStatus.SENT)
        assertTrue(receipt.fromMe)
        assertEquals(MessageStatus.SENT, receipt.status)
        assertEquals(MessageStatus.READ, receipt.copy(status = MessageStatus.READ).status)
    }
}
