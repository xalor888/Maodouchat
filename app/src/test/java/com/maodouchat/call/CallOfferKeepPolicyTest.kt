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
    fun `future timestamp is kept as clock skew`() {
        val now = 1_000_000L
        assertTrue(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "call-a",
                terminatedCallIds = emptySet(),
                timestampMillis = now + 5_000L,
                nowMillis = now
            )
        )
    }

    @Test
    fun `unknown timestamp is kept`() {
        val now = 1_000_000L
        assertTrue(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "call-a",
                terminatedCallIds = emptySet(),
                timestampMillis = 0L,
                nowMillis = now
            )
        )
        assertTrue(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "call-a",
                terminatedCallIds = emptySet(),
                timestampMillis = -1L,
                nowMillis = now
            )
        )
    }

    @Test
    fun `zero max age rejects even fresh offers`() {
        val now = 1_000_000L
        assertFalse(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "call-a",
                terminatedCallIds = emptySet(),
                timestampMillis = now,
                nowMillis = now,
                maxAgeMs = 0L
            )
        )
    }

    @Test
    fun `whitespace-only call id is not treated as terminated`() {
        val now = 1_000_000L
        assertTrue(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "   ",
                terminatedCallIds = setOf("   "),
                timestampMillis = now,
                nowMillis = now
            )
        )
    }

    @Test
    fun `boundary age is kept, one millisecond over is dropped`() {
        val now = 1_000_000L
        assertTrue(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "call-a",
                terminatedCallIds = emptySet(),
                timestampMillis = now - IncomingCallCoordinator.STALE_MS,
                nowMillis = now
            )
        )
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

    @Test
    fun `keeps offer when terminated set names another call`() {
        val now = 1_000_000L
        assertTrue(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "call-b",
                terminatedCallIds = setOf("call-a"),
                timestampMillis = now - 1_000L,
                nowMillis = now
            )
        )
    }

    @Test
    fun `negative max age rejects even fresh offers`() {
        val now = 1_000_000L
        assertFalse(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "call-a",
                terminatedCallIds = emptySet(),
                timestampMillis = now,
                nowMillis = now,
                maxAgeMs = -1L
            )
        )
    }

    @Test
    fun `reject and busy are never kept as incoming offers`() {
        val now = 1_000_000L
        assertFalse(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "reject",
                callId = "call-a",
                terminatedCallIds = emptySet(),
                timestampMillis = now,
                nowMillis = now
            )
        )
        assertFalse(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "busy",
                callId = "call-a",
                terminatedCallIds = emptySet(),
                timestampMillis = now,
                nowMillis = now
            )
        )
    }

    @Test
    fun `offer type with padding is not kept`() {
        // type 比对不裁空白：衬垫过的 type 不等于 offer。
        val now = 1_000_000L
        assertFalse(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = " offer ",
                callId = "call-a",
                terminatedCallIds = emptySet(),
                timestampMillis = now,
                nowMillis = now
            )
        )
    }

    @Test
    fun `case-differing terminated id does not drop the offer`() {
        // 集合匹配精确、大小写敏感：错配不误杀新鲜 offer。
        val now = 1_000_000L
        assertTrue(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "Call-A",
                terminatedCallIds = setOf("call-a"),
                timestampMillis = now - 1_000L,
                nowMillis = now
            )
        )
    }
}
