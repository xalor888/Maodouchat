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

    @Test
    fun exactBoundaryCountsAsExpired() {
        val remaining = SecretSessionTtl.remainingSecondsFor(
            ttlSeconds = 60L,
            lastActivityAt = 1_000_000L,
            nowMs = 1_060_000L,
        )
        assertEquals(0L, remaining)
    }

    @Test
    fun partialRemainingIsReported() {
        val remaining = SecretSessionTtl.remainingSecondsFor(
            ttlSeconds = 60L,
            lastActivityAt = 1_000_000L,
            nowMs = 1_030_000L,
        )
        assertEquals(30L, remaining)
    }

    @Test
    fun zeroTtlExpiresImmediately() {
        val remaining = SecretSessionTtl.remainingSecondsFor(
            ttlSeconds = 0L,
            lastActivityAt = 1_000_000L,
            nowMs = 1_000_000L,
        )
        assertTrue(remaining <= 0L)
    }

    @Test
    fun futureLastActivityYieldsMoreThanTtl() {
        // 时钟回拨/活动时间超前不做钳制：剩余按公式原样算。
        val remaining = SecretSessionTtl.remainingSecondsFor(
            ttlSeconds = 60L,
            lastActivityAt = 2_000_000L,
            nowMs = 1_000_000L,
        )
        assertEquals(1_060L, remaining)
    }

    @Test
    fun subSecondRemainderIsTruncated() {
        val remaining = SecretSessionTtl.remainingSecondsFor(
            ttlSeconds = 60L,
            lastActivityAt = 1_000_000L,
            nowMs = 1_000_500L,
        )
        assertEquals(59L, remaining)
    }
}
