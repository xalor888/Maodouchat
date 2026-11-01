package com.maodouchat.core.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalingOfferFreshnessPolicyTest {

    @Test
    fun unknownTimestampIsAccepted() {
        assertTrue(SignalingOfferFreshnessPolicy.isOfferFresh(0L, nowMillis = 99L))
        assertTrue(SignalingOfferFreshnessPolicy.isOfferFresh(-1L, nowMillis = 99L))
    }

    @Test
    fun staleOfferIsRejected() {
        assertFalse(
            SignalingOfferFreshnessPolicy.isOfferFresh(
                timestampMillis = 1_000L,
                nowMillis = 2_001L,
                maxAgeMs = 1_000L,
            ),
        )
    }

    @Test
    fun futureTimestampIsFresh() {
        assertTrue(
            SignalingOfferFreshnessPolicy.isOfferFresh(
                timestampMillis = 5_000L,
                nowMillis = 1_000L,
                maxAgeMs = 1_000L,
            ),
        )
    }

    @Test
    fun defaultWindowIs120Seconds() {
        assertTrue(SignalingOfferFreshnessPolicy.isOfferFresh(1_000L, nowMillis = 120_000L))
        assertFalse(SignalingOfferFreshnessPolicy.isOfferFresh(1_000L, nowMillis = 121_001L))
    }
}
