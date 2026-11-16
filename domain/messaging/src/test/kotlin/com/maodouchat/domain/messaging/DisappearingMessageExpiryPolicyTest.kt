package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.PinnedMessageDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DisappearingMessageExpiryPolicyTest {

    private fun msg(id: String, expiresAt: Long?) =
        Message(id = id, chatId = "c1", senderId = "u1", content = "hi", expiresAt = expiresAt)

    private fun pin(messageId: String) =
        PinnedMessageDto(chatId = "c1", messageId = messageId, pinnedBy = "u1", pinnedAt = 100L)

    @Test
    fun `expired only when positive deadline reached`() {
        assertFalse(isDisappearingMessageExpired(null, 1000L))
        assertFalse(isDisappearingMessageExpired(0L, 1000L))
        assertFalse(isDisappearingMessageExpired(-5L, 1000L))
        assertFalse(isDisappearingMessageExpired(1001L, 1000L))
        assertTrue(isDisappearingMessageExpired(1000L, 1000L))
        assertTrue(isDisappearingMessageExpired(999L, 1000L))
    }

    @Test
    fun `filterUnexpiredMessages drops only expired`() {
        val messages = listOf(msg("m1", null), msg("m2", 0L), msg("m3", 500L), msg("m4", 1500L))
        assertEquals(listOf("m1", "m2", "m4"), filterUnexpiredMessages(messages, 1000L).map { it.id })
    }

    @Test
    fun `filterUnexpiredPins drops pins of expired messages only`() {
        val messages = listOf(msg("m1", 500L), msg("m2", 1500L))
        val pins = listOf(pin("m1"), pin("m2"), pin("m3"))
        assertEquals(listOf("m2", "m3"), filterUnexpiredPins(pins, messages, 1000L).map { it.messageId })
    }
}
