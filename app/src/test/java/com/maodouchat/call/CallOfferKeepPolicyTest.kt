package com.maodouchat.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * XAL-20：通话契约——冷启动轮询不得重响已结束/过期 offer；非 offer 信令不得当来电保留。
 */
class CallOfferKeepPolicyTest {

    @Test
    fun `keeps a fresh unnamed offer`() {
        val now = 1_000_000L
        assertTrue(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "call-a",
                terminatedCallIds = emptySet(),
                timestampMillis = now - 1_000L,
                nowMillis = now
            )
        )
    }

    @Test
    fun `drops offer for a hang-up terminated call id`() {
        val now = 1_000_000L
        assertFalse(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "call-a",
                terminatedCallIds = setOf("call-a"),
                timestampMillis = now - 1_000L,
                nowMillis = now
            )
        )
    }

    @Test
    fun `drops stale offer beyond coordinator window`() {
        val now = 1_000_000L
        assertFalse(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "call-a",
                terminatedCallIds = emptySet(),
                timestampMillis = now - IncomingCallCoordinator.STALE_MS - 1L,
                nowMillis = now
            )
        )
    }

    @Test
    fun `answer and hangup are never kept as incoming offers`() {
        val now = 1_000_000L
        assertFalse(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "answer",
                callId = "call-a",
                terminatedCallIds = emptySet(),
                timestampMillis = now,
                nowMillis = now
            )
        )
        assertFalse(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "hangup",
                callId = "call-a",
                terminatedCallIds = emptySet(),
                timestampMillis = now,
                nowMillis = now
            )
        )
    }

    @Test
    fun `blank call id is not treated as terminated`() {
        val now = 1_000_000L
        assertTrue(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "",
                terminatedCallIds = setOf("call-a"),
                timestampMillis = now,
                nowMillis = now
            )
        )
    }
}
