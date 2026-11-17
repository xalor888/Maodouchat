package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DisappearingMessagePolicyTest {

    @Test
    fun `group chat is always off and secret chat is fixed at 30 seconds`() {
        assertEquals(
            DisappearingMessagePolicy.OFF_SECONDS,
            DisappearingMessagePolicy.effectiveSeconds(isGroup = true, requestedSeconds = 3600)
        )
        assertEquals(
            DisappearingMessagePolicy.SECRET_DEFAULT_SECONDS,
            DisappearingMessagePolicy.effectiveSeconds(
                isGroup = false, requestedSeconds = 3600, isSecret = true
            )
        )
    }

    @Test
    fun `existing deadline is never rewritten`() {
        assertEquals(
            2_000L,
            DisappearingMessagePolicy.resolveExpiresAt(2_000L, timerSeconds = 30, readAtMs = 5_000L)
        )
        assertEquals(
            31_000L,
            DisappearingMessagePolicy.resolveExpiresAt(null, timerSeconds = 30, readAtMs = 1_000L)
        )
    }

    @Test
    fun `expiry boundary and remaining time clamp`() {
        assertTrue(DisappearingMessagePolicy.isExpired(1_000L, 1_000L))
        assertFalse(DisappearingMessagePolicy.isExpired(null, 1_000L))
        assertEquals(0L, DisappearingMessagePolicy.remainingMs(1_000L, 2_000L))
        assertEquals(-1L, DisappearingMessagePolicy.remainingMs(null, 1_000L))
    }
}
