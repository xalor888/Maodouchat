package com.maodouchat.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecretSessionTtlTest {

    @Test
    fun unknownLastActivityFailsClosedToExpired() {
        val remaining = SecretSessionTtl.remainingSecondsFor(
            ttlSeconds = 86_400L,
            lastActivityAt = 0L,
            nowMs = 1_000_000L,
        )
        assertTrue(remaining <= 0L)
    }

    @Test
    fun negativeLastActivityFailsClosedToExpired() {
        val remaining = SecretSessionTtl.remainingSecondsFor(
            ttlSeconds = 86_400L,
            lastActivityAt = -1L,
            nowMs = 1_000_000L,
        )
        assertTrue(remaining <= 0L)
    }

    @Test
    fun activeSessionStillExpiresByTtl() {
        val remaining = SecretSessionTtl.remainingSecondsFor(
            ttlSeconds = 60L,
            lastActivityAt = 1_000_000L,
            nowMs = 1_000_000L,
        )
        assertEquals(60L, remaining)
    }
}
