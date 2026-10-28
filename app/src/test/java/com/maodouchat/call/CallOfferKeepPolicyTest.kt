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

    @Test
    fun `uppercase offer type is kept`() {
        // type 比对大小写不敏感：大写 OFFER 仍是 offer。
        val now = 1_000_000L
        assertTrue(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "OFFER",
                callId = "call-a",
                terminatedCallIds = emptySet(),
                timestampMillis = now - 1_000L,
                nowMillis = now
            )
        )
    }

    @Test
    fun `custom max age window is honored`() {
        val now = 1_000_000L
        assertTrue(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "call-a",
                terminatedCallIds = emptySet(),
                timestampMillis = now - 500L,
                nowMillis = now,
                maxAgeMs = 1_000L
            )
        )
        assertFalse(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "call-a",
                terminatedCallIds = emptySet(),
                timestampMillis = now - 500L,
                nowMillis = now,
                maxAgeMs = 499L
            )
        )
    }

    @Test
    fun `far future timestamp is kept despite tiny window`() {
        // 时钟偏移：未来时间戳恒判新鲜，不看窗口大小。
        val now = 1_000_000L
        assertTrue(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "call-a",
                terminatedCallIds = emptySet(),
                timestampMillis = now + 1_000_000L,
                nowMillis = now,
                maxAgeMs = 1L
            )
        )
    }

    @Test
    fun `zero timestamp is kept despite zero max age`() {
        // 时间戳未知（<=0）先于窗口判定：宁可走挂断路径也不静默丢弃存量 offer。
        val now = 1_000_000L
        assertTrue(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "call-a",
                terminatedCallIds = emptySet(),
                timestampMillis = 0L,
                nowMillis = now,
                maxAgeMs = 0L
            )
        )
    }

    @Test
    fun `padded terminated entry does not match`() {
        // 集合匹配精确不裁边：衬垫过的条目不误杀新鲜 offer。
        val now = 1_000_000L
        assertTrue(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "call-a",
                terminatedCallIds = setOf(" call-a "),
                timestampMillis = now - 1_000L,
                nowMillis = now
            )
        )
    }

    @Test
    fun `mixed case offer type is kept`() {
        val now = 1_000_000L
        assertTrue(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "Offer",
                callId = "call-a",
                terminatedCallIds = emptySet(),
                timestampMillis = now - 1_000L,
                nowMillis = now
            )
        )
    }

    @Test
    fun `candidate type is never kept as incoming offer`() {
        val now = 1_000_000L
        assertFalse(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "candidate",
                callId = "call-a",
                terminatedCallIds = emptySet(),
                timestampMillis = now,
                nowMillis = now
            )
        )
    }

    @Test
    fun `blank id still loses to a stale timestamp`() {
        // 空白 id 只豁免终止集匹配，不豁免新鲜度窗口。
        val now = 1_000_000L
        assertFalse(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "",
                terminatedCallIds = emptySet(),
                timestampMillis = now - IncomingCallCoordinator.STALE_MS - 1L,
                nowMillis = now
            )
        )
    }

    @Test
    fun `negative timestamp is kept despite negative window`() {
        // 时间戳未知先于窗口判定：负时间戳恒放行，不看窗口正负。
        val now = 1_000_000L
        assertTrue(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "call-a",
                terminatedCallIds = emptySet(),
                timestampMillis = -5L,
                nowMillis = now,
                maxAgeMs = -1L
            )
        )
    }

    @Test
    fun `terminated set of only blank entries does not drop`() {
        val now = 1_000_000L
        assertTrue(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "call-a",
                terminatedCallIds = setOf("", "   "),
                timestampMillis = now - 1_000L,
                nowMillis = now
            )
        )
    }
}
