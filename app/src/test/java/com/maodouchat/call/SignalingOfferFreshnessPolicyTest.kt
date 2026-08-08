package com.maodouchat.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalingOfferFreshnessPolicyTest {
    @Test
    fun freshOfferWithinWindow() {
        val now = 1_000_000L
        assertTrue(
            SignalingOfferFreshnessPolicy.isOfferFresh(
                timestampMillis = now - 30_000L,
                nowMillis = now,
            )
        )
        assertTrue(
            SignalingOfferFreshnessPolicy.isOfferFresh(
                timestampMillis = now - IncomingCallCoordinator.STALE_MS,
                nowMillis = now,
            )
        )
    }

    @Test
    fun staleOfferBeyondWindow() {
        val now = 1_000_000L
        assertFalse(
            SignalingOfferFreshnessPolicy.isOfferFresh(
                timestampMillis = now - IncomingCallCoordinator.STALE_MS - 1L,
                nowMillis = now,
            )
        )
    }

    @Test
    fun unknownTimestampAccepted() {
        assertTrue(SignalingOfferFreshnessPolicy.isOfferFresh(0L, nowMillis = 99L))
        assertTrue(SignalingOfferFreshnessPolicy.isOfferFresh(-1L, nowMillis = 99L))
    }

    @Test
    fun futureTimestampAcceptedAsClockSkew() {
        assertTrue(
            SignalingOfferFreshnessPolicy.isOfferFresh(
                timestampMillis = 2_000L,
                nowMillis = 1_000L,
            )
        )
    }

    @Test
    fun shouldKeepOfferFiltersTypeTerminalAndAge() {
        // now must exceed STALE_MS so (now - STALE - 5) stays positive (unknown-ts path is separate).
        val now = 1_000_000L
        assertTrue(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "c1",
                terminatedCallIds = emptySet(),
                timestampMillis = now - 10_000L,
                nowMillis = now,
            )
        )
        assertFalse(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "answer",
                callId = "c1",
                terminatedCallIds = emptySet(),
                timestampMillis = now - 10_000L,
                nowMillis = now,
            )
        )
        assertFalse(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "c1",
                terminatedCallIds = setOf("c1"),
                timestampMillis = now - 10_000L,
                nowMillis = now,
            )
        )
        assertFalse(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "c2",
                terminatedCallIds = emptySet(),
                timestampMillis = now - IncomingCallCoordinator.STALE_MS - 5L,
                nowMillis = now,
            )
        )
    }
}
