package com.maodouchat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisappearingMessagePolicyTest {
    @Test
    fun `normalize rejects unknown timers`() {
        assertEquals(0, DisappearingMessagePolicy.normalizeSeconds(null))
        assertEquals(0, DisappearingMessagePolicy.normalizeSeconds(90))
        assertEquals(60, DisappearingMessagePolicy.normalizeSeconds(60))
        assertEquals(24 * 60 * 60, DisappearingMessagePolicy.normalizeSeconds(86_400))
    }

    @Test
    fun `groups always force off`() {
        assertEquals(0, DisappearingMessagePolicy.effectiveSeconds(isGroup = true, requestedSeconds = 60))
        assertEquals(60, DisappearingMessagePolicy.effectiveSeconds(isGroup = false, requestedSeconds = 60))
        assertEquals(30, DisappearingMessagePolicy.effectiveSeconds(isGroup = false, requestedSeconds = 0, isSecret = true))
        assertEquals(30, DisappearingMessagePolicy.effectiveSeconds(isGroup = false, requestedSeconds = 86_400, isSecret = true))
    }

    @Test
    fun `expiresAt is set once on first read`() {
        val first = DisappearingMessagePolicy.resolveExpiresAt(
            existingExpiresAt = null,
            timerSeconds = 60,
            readAtMs = 1_000L
        )
        assertEquals(61_000L, first)
        val second = DisappearingMessagePolicy.resolveExpiresAt(
            existingExpiresAt = first,
            timerSeconds = 60,
            readAtMs = 50_000L
        )
        assertEquals(first, second)
    }

    @Test
    fun `timer off leaves expiresAt untouched`() {
        assertNull(
            DisappearingMessagePolicy.resolveExpiresAt(
                existingExpiresAt = null,
                timerSeconds = 0,
                readAtMs = 1_000L
            )
        )
        assertEquals(
            9_000L,
            DisappearingMessagePolicy.resolveExpiresAt(
                existingExpiresAt = 9_000L,
                timerSeconds = 0,
                readAtMs = 1_000L
            )
        )
    }

    @Test
    fun `secret chat arms timer on visible without read receipts`() {
        assertTrue(DisappearingMessagePolicy.shouldArmOnVisible(isSecretChat = true, timerSeconds = 30))
        assertTrue(DisappearingMessagePolicy.shouldArmOnVisible(isSecretChat = true, timerSeconds = 0))
        assertFalse(DisappearingMessagePolicy.shouldArmOnVisible(isSecretChat = false, timerSeconds = 30))
        assertTrue(DisappearingMessagePolicy.shouldSkipReadReceipts(isSecretChat = true, blockReadReceipts = true))
        assertTrue(DisappearingMessagePolicy.shouldSkipReadReceipts(isSecretChat = true, blockReadReceipts = false))
        assertFalse(DisappearingMessagePolicy.shouldSkipReadReceipts(isSecretChat = false, blockReadReceipts = true))
    }

    @Test
    fun `expiry helpers`() {
        assertFalse(DisappearingMessagePolicy.isExpired(null, 100))
        assertFalse(DisappearingMessagePolicy.isExpired(200L, 100))
        assertTrue(DisappearingMessagePolicy.isExpired(200L, 200))
        assertEquals(50L, DisappearingMessagePolicy.remainingMs(150L, 100L))
        assertEquals(0L, DisappearingMessagePolicy.remainingMs(50L, 100L))
        assertEquals(-1L, DisappearingMessagePolicy.remainingMs(null, 100L))
    }
}
