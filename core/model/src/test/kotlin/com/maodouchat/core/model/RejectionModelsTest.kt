package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RejectionModelsTest {

    @Test
    fun `schedule rejections keep declaration order and valueOf round trip`() {
        val reasons = ChatScheduleRejection.entries.toList()
        assertEquals(7, reasons.size)
        assertEquals(ChatScheduleRejection.DISABLED, reasons.first())
        assertEquals(
            ChatScheduleRejection.STORAGE,
            ChatScheduleRejection.valueOf("STORAGE")
        )
    }

    @Test
    fun `schedule failures keep declaration order and valueOf round trip`() {
        val failures = ConversationScheduleFailure.entries.toList()
        assertEquals(5, failures.size)
        assertEquals(ConversationScheduleFailure.SESSION_MISSING, failures.first())
        assertEquals(
            ConversationScheduleFailure.NOT_FOUND,
            ConversationScheduleFailure.valueOf("NOT_FOUND")
        )
    }
}
