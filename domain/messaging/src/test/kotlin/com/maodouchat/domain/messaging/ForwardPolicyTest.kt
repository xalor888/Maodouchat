package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ForwardPolicyTest {

    @Test
    fun `secret chat is blocked`() {
        assertEquals(
            Forwardability.SECRET_CHAT_BLOCKED,
            ForwardPolicy.evaluate(isSecretChat = true, isTerminalMessage = false, senderForbidsForward = false),
        )
    }

    @Test
    fun `terminal message is blocked`() {
        assertEquals(
            Forwardability.TERMINAL_BLOCKED,
            ForwardPolicy.evaluate(isSecretChat = false, isTerminalMessage = true, senderForbidsForward = false),
        )
    }

    @Test
    fun `sender privacy is blocked`() {
        assertEquals(
            Forwardability.PRIVACY_BLOCKED,
            ForwardPolicy.evaluate(isSecretChat = false, isTerminalMessage = false, senderForbidsForward = true),
        )
    }

    @Test
    fun `plain message is allowed`() {
        assertEquals(
            Forwardability.ALLOWED,
            ForwardPolicy.evaluate(isSecretChat = false, isTerminalMessage = false, senderForbidsForward = false),
        )
    }
}
