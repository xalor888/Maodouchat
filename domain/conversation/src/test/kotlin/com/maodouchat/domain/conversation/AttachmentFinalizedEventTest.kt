package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Message
import kotlin.test.Test
import kotlin.test.assertEquals

class AttachmentFinalizedEventTest {

    @Test
    fun `attachment finalized event keeps message and generation`() {
        val message = Message(id = "m1", chatId = "c1", senderId = "u1", content = "hi")
        val event = AttachmentFinalizedEvent(message = message, sessionGeneration = 5L)
        assertEquals("m1", event.message.id)
        assertEquals(5L, event.sessionGeneration)
    }
}
