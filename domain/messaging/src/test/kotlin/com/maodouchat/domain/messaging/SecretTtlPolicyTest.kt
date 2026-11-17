package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecretTtlPolicyTest {

    @Test
    fun belowMinClampsToMin() {
        assertEquals(SecretTtlPolicy.MIN_TTL_SECONDS, SecretTtlPolicy.clampTtlSeconds(0L))
        assertEquals(SecretTtlPolicy.MIN_TTL_SECONDS, SecretTtlPolicy.clampTtlSeconds(-5L))
    }

    @Test
    fun aboveMaxClampsToMax() {
        assertEquals(SecretTtlPolicy.MAX_TTL_SECONDS, SecretTtlPolicy.clampTtlSeconds(Long.MAX_VALUE))
    }

    @Test
    fun inRangeUnchanged() {
        assertEquals(3_600L, SecretTtlPolicy.clampTtlSeconds(3_600L))
    }

    @Test
    fun defaultInRangeAndBoundsOrdered() {
        assertTrue(SecretTtlPolicy.DEFAULT_TTL_SECONDS in
            SecretTtlPolicy.MIN_TTL_SECONDS..SecretTtlPolicy.MAX_TTL_SECONDS)
        assertTrue(SecretTtlPolicy.MIN_TTL_SECONDS < SecretTtlPolicy.MAX_TTL_SECONDS)
        assertEquals(86_400L, SecretTtlPolicy.DEFAULT_TTL_SECONDS)
    }

    @Test
    fun maxIsThirtyDays() {
        assertEquals(30L * 24 * 60 * 60, SecretTtlPolicy.MAX_TTL_SECONDS)
    }
}
