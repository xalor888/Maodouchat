package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretGateTimeoutPolicyTest {

    @Test
    fun belowMinClampsToMin() {
        assertEquals(SecretGateTimeoutPolicy.MIN_TIMEOUT_MS, SecretGateTimeoutPolicy.clampTimeoutMs(0L))
    }

    @Test
    fun aboveMaxClampsToMax() {
        assertEquals(SecretGateTimeoutPolicy.MAX_TIMEOUT_MS, SecretGateTimeoutPolicy.clampTimeoutMs(Long.MAX_VALUE))
    }

    @Test
    fun inRangeUnchanged() {
        assertEquals(60_000L, SecretGateTimeoutPolicy.clampTimeoutMs(60_000L))
    }

    @Test
    fun defaultIsFiveMinutes() {
        assertEquals(5L * 60 * 1000, SecretGateTimeoutPolicy.DEFAULT_TIMEOUT_MS)
    }

    @Test
    fun neverVerifiedMeansClosed() {
        assertFalse(SecretGateTimeoutPolicy.isGateOpen(0L, 1_000L, 300_000L))
    }

    @Test
    fun withinWindowMeansOpen() {
        assertTrue(SecretGateTimeoutPolicy.isGateOpen(1_000L, 100_000L, 300_000L))
    }

    @Test
    fun expiredWindowMeansClosed() {
        assertFalse(SecretGateTimeoutPolicy.isGateOpen(1_000L, 400_000L, 300_000L))
    }

    @Test
    fun exactlyAtTimeoutMeansClosed() {
        assertFalse(SecretGateTimeoutPolicy.isGateOpen(1_000L, 301_000L, 300_000L))
    }
}
