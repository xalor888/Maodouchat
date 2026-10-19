package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
    fun `pin locked chat is blocked`() {
        assertEquals(
            Forwardability.PIN_LOCKED_BLOCKED,
            ForwardPolicy.evaluate(
                isSecretChat = false,
                isTerminalMessage = false,
                senderForbidsForward = false,
                isPinLocked = true,
            ),
        )
    }

    @Test
    fun `plain message is allowed`() {
        assertEquals(
            Forwardability.ALLOWED,
            ForwardPolicy.evaluate(isSecretChat = false, isTerminalMessage = false, senderForbidsForward = false),
        )
    }

    @Test
    fun `pin lock dominates all other blocks`() {
        assertEquals(
            Forwardability.PIN_LOCKED_BLOCKED,
            ForwardPolicy.evaluate(
                isSecretChat = true,
                isTerminalMessage = true,
                senderForbidsForward = true,
                isPinLocked = true,
            ),
        )
    }

    @Test
    fun `secret chat dominates terminal message`() {
        assertEquals(
            Forwardability.SECRET_CHAT_BLOCKED,
            ForwardPolicy.evaluate(isSecretChat = true, isTerminalMessage = true, senderForbidsForward = false),
        )
    }

    @Test
    fun `terminal message dominates sender privacy`() {
        assertEquals(
            Forwardability.TERMINAL_BLOCKED,
            ForwardPolicy.evaluate(isSecretChat = false, isTerminalMessage = true, senderForbidsForward = true),
        )
    }

    @Test
    fun `secret chat dominates sender privacy`() {
        assertEquals(
            Forwardability.SECRET_CHAT_BLOCKED,
            ForwardPolicy.evaluate(isSecretChat = true, isTerminalMessage = false, senderForbidsForward = true),
        )
    }

    @Test
    fun `default pin lock param behaves as unlocked`() {
        assertEquals(
            ForwardPolicy.evaluate(isSecretChat = false, isTerminalMessage = false, senderForbidsForward = false),
            ForwardPolicy.evaluate(
                isSecretChat = false,
                isTerminalMessage = false,
                senderForbidsForward = false,
                isPinLocked = false,
            ),
        )
    }

    @Test
    fun `sixteen flag combinations form the complete decision grid`() {
        val flags = listOf(false, true)
        for (pin in flags) for (secret in flags) for (terminal in flags) for (privacy in flags) {
            val expected = when {
                pin -> Forwardability.PIN_LOCKED_BLOCKED
                secret -> Forwardability.SECRET_CHAT_BLOCKED
                terminal -> Forwardability.TERMINAL_BLOCKED
                privacy -> Forwardability.PRIVACY_BLOCKED
                else -> Forwardability.ALLOWED
            }
            assertEquals(
                expected,
                ForwardPolicy.evaluate(
                    isSecretChat = secret,
                    isTerminalMessage = terminal,
                    senderForbidsForward = privacy,
                    isPinLocked = pin,
                ),
                "pin=$pin secret=$secret terminal=$terminal privacy=$privacy",
            )
        }
    }

    @Test
    fun `pin lock dominates each block individually`() {
        assertEquals(
            Forwardability.PIN_LOCKED_BLOCKED,
            ForwardPolicy.evaluate(
                isSecretChat = true,
                isTerminalMessage = false,
                senderForbidsForward = false,
                isPinLocked = true,
            ),
        )
        assertEquals(
            Forwardability.PIN_LOCKED_BLOCKED,
            ForwardPolicy.evaluate(
                isSecretChat = false,
                isTerminalMessage = true,
                senderForbidsForward = false,
                isPinLocked = true,
            ),
        )
        assertEquals(
            Forwardability.PIN_LOCKED_BLOCKED,
            ForwardPolicy.evaluate(
                isSecretChat = false,
                isTerminalMessage = false,
                senderForbidsForward = true,
                isPinLocked = true,
            ),
        )
    }

    @Test
    fun `failure reason taxonomy is stable at five values`() {
        assertEquals(
            listOf("NOT_READY", "FORBIDDEN", "PERMANENT", "CANCELLED", "PIN_LOCKED"),
            ForwardFailureReason.entries.map { it.name },
        )
        ForwardFailureReason.entries.forEach { assertEquals(it, ForwardFailureReason.valueOf(it.name)) }
    }

    @Test
    fun `forward request defaults and idempotency key identity`() {
        val minimal = ForwardRequest("m1", "c1", listOf("c2"))
        assertNull(minimal.caption)
        assertEquals("", minimal.idempotencyKey)
        assertEquals(minimal, minimal.copy())
        assertFalse(minimal == minimal.copy(idempotencyKey = "k1"))
        assertEquals("k1", minimal.copy(idempotencyKey = "k1").idempotencyKey)
    }

    @Test
    fun `evaluate is stateless across repeated calls`() {
        repeat(3) {
            assertEquals(
                Forwardability.SECRET_CHAT_BLOCKED,
                ForwardPolicy.evaluate(isSecretChat = true, isTerminalMessage = true, senderForbidsForward = true),
            )
            assertEquals(
                Forwardability.ALLOWED,
                ForwardPolicy.evaluate(isSecretChat = false, isTerminalMessage = false, senderForbidsForward = false),
            )
        }
    }
}
