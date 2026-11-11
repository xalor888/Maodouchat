package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatDetailUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatContactCardControllerTest {

    private class Harness(initial: ChatDetailUiState) {
        var live: ChatDetailUiState = initial
        var enabled: Boolean = true
        val sent = mutableListOf<String>()

        val controller = ChatContactCardController(
            contactCardEnabled = { enabled },
            text = { message -> "[${message.name}]" },
            currentState = { live },
            updateState = { transform -> live = transform(live) },
            sendMessage = { sent += it },
        )
    }

    private fun harness(initial: ChatDetailUiState = ChatDetailUiState()) = Harness(initial)

    @Test
    fun `flag off warns and does not send`() {
        val h = harness()
        h.enabled = false
        h.controller.sendContactCard("u9", "Alice")

        assertEquals("[CONTACT_CARD_DISABLED]", h.live.groupEncryptionWarning)
        assertTrue(h.sent.isEmpty())
    }

    @Test
    fun `secret chat is blocked`() {
        val h = harness(ChatDetailUiState(isSecretChat = true))
        h.controller.sendContactCard("u9", "Alice")

        assertEquals("[CONTACT_CARD_SECRET_BLOCKED]", h.live.groupEncryptionWarning)
        assertTrue(h.sent.isEmpty())
    }

    @Test
    fun `blank target is no-op`() {
        val h = harness()
        h.controller.sendContactCard("", "Alice")

        assertNull(h.live.groupEncryptionWarning)
        assertTrue(h.sent.isEmpty())
    }

    @Test
    fun `happy path sends card content`() {
        val h = harness()
        h.controller.sendContactCard("u9", "Alice")

        assertEquals(listOf("👤 Alice\n[contactUser:u9]"), h.sent)
        assertNull(h.live.groupEncryptionWarning)
    }

    @Test
    fun `blank name falls back to contact`() {
        val h = harness()
        h.controller.sendContactCard("u9", "   ")

        assertEquals(listOf("👤 contact\n[contactUser:u9]"), h.sent)
    }

    @Test
    fun `long name is truncated to 80 chars`() {
        val h = harness()
        val longName = "n".repeat(100)
        h.controller.sendContactCard("u9", longName)

        assertEquals(listOf("👤 " + "n".repeat(80) + "\n[contactUser:u9]"), h.sent)
    }
}
