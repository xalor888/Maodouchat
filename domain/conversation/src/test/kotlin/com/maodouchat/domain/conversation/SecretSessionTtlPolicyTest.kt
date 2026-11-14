package com.maodouchat.domain.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecretSessionTtlPolicyTest {

    @Test
    fun unknownLastActivityFailsClosedToExpired() {
        val remaining = SecretSessionTtlPolicy.remainingSecondsFor(
            ttlSeconds = 86_400L,
            lastActivityAt = 0L,
            nowMs = 1_000_000L,
        )
        assertTrue(remaining <= 0L)
    }

    @Test
    fun negativeLastActivityFailsClosedToExpired() {
        val remaining = SecretSessionTtlPolicy.remainingSecondsFor(
            ttlSeconds = 86_400L,
            lastActivityAt = -1L,
            nowMs = 1_000_000L,
        )
        assertTrue(remaining <= 0L)
    }

    @Test
    fun activeSessionStillExpiresByTtl() {
        val remaining = SecretSessionTtlPolicy.remainingSecondsFor(
            ttlSeconds = 60L,
            lastActivityAt = 1_000_000L,
            nowMs = 1_000_000L,
        )
        assertEquals(60L, remaining)
    }

    @Test
    fun exactBoundaryCountsAsExpired() {
        val remaining = SecretSessionTtlPolicy.remainingSecondsFor(
            ttlSeconds = 60L,
            lastActivityAt = 1_000_000L,
            nowMs = 1_060_000L,
        )
        assertEquals(0L, remaining)
    }

    @Test
    fun partialRemainingIsReported() {
        val remaining = SecretSessionTtlPolicy.remainingSecondsFor(
            ttlSeconds = 60L,
            lastActivityAt = 1_000_000L,
            nowMs = 1_030_000L,
        )
        assertEquals(30L, remaining)
    }

    @Test
    fun zeroTtlExpiresImmediately() {
        val remaining = SecretSessionTtlPolicy.remainingSecondsFor(
            ttlSeconds = 0L,
            lastActivityAt = 1_000_000L,
            nowMs = 1_000_000L,
        )
        assertTrue(remaining <= 0L)
    }

    @Test
    fun futureLastActivityYieldsMoreThanTtl() {
        // 时钟回拨/活动时间超前不做钳制：剩余按公式原样算。
        val remaining = SecretSessionTtlPolicy.remainingSecondsFor(
            ttlSeconds = 60L,
            lastActivityAt = 2_000_000L,
            nowMs = 1_000_000L,
        )
        assertEquals(1_060L, remaining)
    }

    @Test
    fun subSecondRemainderIsTruncated() {
        val remaining = SecretSessionTtlPolicy.remainingSecondsFor(
            ttlSeconds = 60L,
            lastActivityAt = 1_000_000L,
            nowMs = 1_000_500L,
        )
        assertEquals(59L, remaining)
    }

    @Test
    fun negativeTtlExpiresImmediately() {
        val remaining = SecretSessionTtlPolicy.remainingSecondsFor(
            ttlSeconds = -60L,
            lastActivityAt = 1_000_000L,
            nowMs = 1_000_000L,
        )
        assertEquals(-60L, remaining)
    }

    @Test
    fun oneMillisecondPastExpiryTruncatesToZero() {
        // 整数除法向零截断：-1ms 的剩余按 0 算，仍满足 isExpired 的 <=0 判定。
        val remaining = SecretSessionTtlPolicy.remainingSecondsFor(
            ttlSeconds = 60L,
            lastActivityAt = 1_000_000L,
            nowMs = 1_060_001L,
        )
        assertEquals(0L, remaining)
    }

    @Test
    fun oneSecondPastExpiryReportsNegativeOne() {
        val remaining = SecretSessionTtlPolicy.remainingSecondsFor(
            ttlSeconds = 60L,
            lastActivityAt = 1_000_000L,
            nowMs = 1_061_000L,
        )
        assertEquals(-1L, remaining)
    }

    @Test
    fun tinyPositiveLastActivityUsesFormulaNotFailClosed() {
        // fail-closed 只认 <=0；1ms 的活动时间照公式算，不走快速过期分支。
        val remaining = SecretSessionTtlPolicy.remainingSecondsFor(
            ttlSeconds = 60L,
            lastActivityAt = 1L,
            nowMs = 1_000_000L,
        )
        assertEquals(-939L, remaining)
    }

    @Test
    fun largeTtlHasNoOverflow() {
        val remaining = SecretSessionTtlPolicy.remainingSecondsFor(
            ttlSeconds = 315_360_000L,
            lastActivityAt = 1_000_000L,
            nowMs = 1_000_000L,
        )
        assertEquals(315_360_000L, remaining)
    }
}
