package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AttachmentSendResultTest {

    private fun message() = Message(id = "m1", chatId = "c1", senderId = "u1", content = "hi")

    @Test
    fun `existing transfer is a singleton`() {
        assertEquals(AttachmentSendResult.ExistingTransfer, AttachmentSendResult.ExistingTransfer)
    }

    @Test
    fun `queued carries the message`() {
        val result = AttachmentSendResult.Queued(message())
        assertIs<AttachmentSendResult.Queued>(result)
        assertEquals("m1", result.message.id)
    }

    @Test
    fun `when over result is exhaustive`() {
        val queued: AttachmentSendResult = AttachmentSendResult.Queued(message())
        val label = when (queued) {
            AttachmentSendResult.ExistingTransfer -> "existing"
            is AttachmentSendResult.Queued -> "queued:${queued.message.id}"
        }
        assertEquals("queued:m1", label)
    }
}
