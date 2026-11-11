package com.maodouchat.domain.conversation

import com.maodouchat.core.model.CaptureAlertPolicy
import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCaptureAlertControllerTest {

    private class Harness(initial: ChatDetailUiState) {
        var live: ChatDetailUiState = initial
        var activeChatId: String = "c1"
        var enabled: Boolean = true
        var label: String = "alice123456789"
        var now: Long = 100_000L
        var lastNotify: Long = 0L
        val sent = mutableListOf<Triple<String, MessageType, String>>()

        val controller = ChatCaptureAlertController(
            activeChatId = { activeChatId },
            currentState = { live },
            updateState = { transform -> live = transform(live) },
            sendInlineContent = { content, type, preview ->
                sent += Triple(content, type, preview)
                "m1"
            },
            getLastPeerNotifyAt = { lastNotify },
            setLastPeerNotifyAt = { lastNotify = it },
            captureAlertEnabled = { enabled },
            currentUserLabel = { label },
            nowMillis = { now },
        )

        fun expectedContent() = CaptureAlertPolicy.format(label, "screenshot")
    }

    private fun harness(initial: ChatDetailUiState = ChatDetailUiState()) = Harness(initial)

    @Test
    fun `blank message is no-op`() {
        val h = harness()
        h.controller.notifyLocalCaptureDetected("   ")

        assertNull(h.live.groupEncryptionWarning)
        assertTrue(h.sent.isEmpty())
    }

    @Test
    fun `plain chat warns locally only`() {
        val h = harness()
        h.controller.notifyLocalCaptureDetected("screenshot detected")

        assertEquals("screenshot detected", h.live.groupEncryptionWarning)
        assertNull(h.live.secretChatInfoMessage)
        assertTrue(h.sent.isEmpty())
    }

    @Test
    fun `secret chat warns peer`() {
        val h = harness(ChatDetailUiState(isSecretChat = true, chat = Chat(id = "c1")))
        h.controller.notifyLocalCaptureDetected("screenshot detected")

        assertEquals("screenshot detected", h.live.groupEncryptionWarning)
        assertEquals("screenshot detected", h.live.secretChatInfoMessage)
        assertEquals(
            listOf(Triple(h.expectedContent(), MessageType.TEXT, h.expectedContent())),
            h.sent,
        )
        assertEquals(100_000L, h.lastNotify)
    }

    @Test
    fun `disappearing chat warns peer`() {
        val h = harness(
            ChatDetailUiState(chat = Chat(id = "c1", disappearingMessageSeconds = 60))
        )
        h.controller.notifyLocalCaptureDetected("screenshot detected")

        assertEquals(1, h.sent.size)
        assertEquals(h.expectedContent(), h.sent.single().first)
    }

    @Test
    fun `group chat never warns peer`() {
        val h = harness(
            ChatDetailUiState(isSecretChat = true, chat = Chat(id = "c1", isGroup = true))
        )
        h.controller.notifyLocalCaptureDetected("screenshot detected")

        assertEquals("screenshot detected", h.live.groupEncryptionWarning)
        assertTrue(h.sent.isEmpty())
    }

    @Test
    fun `flag off never warns peer`() {
        val h = harness(ChatDetailUiState(isSecretChat = true, chat = Chat(id = "c1")))
        h.enabled = false
        h.controller.notifyLocalCaptureDetected("screenshot detected")

        assertEquals("screenshot detected", h.live.groupEncryptionWarning)
        assertTrue(h.sent.isEmpty())
    }

    @Test
    fun `second alert within 8s is throttled`() {
        val h = harness(ChatDetailUiState(isSecretChat = true, chat = Chat(id = "c1")))
        h.controller.notifyLocalCaptureDetected("first")
        h.now = 101_000L
        h.controller.notifyLocalCaptureDetected("second")

        assertEquals(1, h.sent.size)
        assertEquals(100_000L, h.lastNotify)
    }

    @Test
    fun `alert after 8s is sent again`() {
        val h = harness(ChatDetailUiState(isSecretChat = true, chat = Chat(id = "c1")))
        h.controller.notifyLocalCaptureDetected("first")
        h.now = 108_000L
        h.controller.notifyLocalCaptureDetected("second")

        assertEquals(2, h.sent.size)
        assertEquals(108_000L, h.lastNotify)
    }

    @Test
    fun `blank chat id does not send but updates throttle`() {
        val h = harness(ChatDetailUiState(isSecretChat = true, chat = Chat(id = "c1")))
        h.activeChatId = ""
        h.controller.notifyLocalCaptureDetected("screenshot detected")

        assertEquals("screenshot detected", h.live.groupEncryptionWarning)
        assertTrue(h.sent.isEmpty())
        assertEquals(100_000L, h.lastNotify)
    }
}
