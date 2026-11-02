package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageReminderTest {

    @Test
    fun `copy keeps fields and equality is structural`() {
        val r = MessageReminder("id1", "chat1", "msg1", "preview", 1_000L, 500L, "user1")
        assertEquals(r, r.copy())
        assertEquals(2_000L, r.copy(remindAtMillis = 2_000L).remindAtMillis)
        assertEquals("preview", r.copy().messagePreview)
    }
}
