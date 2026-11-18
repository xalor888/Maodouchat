package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class QuickReplyCommandModelsTest {

    @Test
    fun `command holds owner chat and text`() {
        val command = QuickReplyCommand("owner", "chat", "hello")
        assertEquals("owner", command.ownerUserId)
        assertEquals("chat", command.chatId)
        assertEquals("hello", command.text)
    }

    @Test
    fun `result variants are distinct`() {
        assertNotEquals(
            QuickReplyCommandResult.Sent as Any,
            QuickReplyCommandResult.Duplicate as Any,
        )
        assertEquals(
            "invalid_command",
            (QuickReplyCommandResult.Rejected("invalid_command") as QuickReplyCommandResult.Rejected).reason,
        )
    }
}
