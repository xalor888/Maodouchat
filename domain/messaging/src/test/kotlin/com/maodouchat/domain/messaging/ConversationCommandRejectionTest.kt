package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationCommandRejectionTest {

    @Test
    fun `command rejections keep declaration order and valueOf round trip`() {
        val reasons = ConversationCommandRejection.entries.toList()
        assertEquals(5, reasons.size)
        assertEquals(ConversationCommandRejection.EMPTY_TEXT, reasons.first())
        assertEquals(
            ConversationCommandRejection.LOCKED_CONVERSATION,
            ConversationCommandRejection.valueOf("LOCKED_CONVERSATION")
        )
    }
}
