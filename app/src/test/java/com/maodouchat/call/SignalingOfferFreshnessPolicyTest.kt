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
    fun zeroMaxAgeWindowRejectsEvenFreshOffers() {
        val now = 1_000_000L
        assertFalse(
            SignalingOfferFreshnessPolicy.isOfferFresh(
                timestampMillis = now,
                nowMillis = now,
                maxAgeMs = 0L,
            )
        )
    }

    @Test
    fun negativeMaxAgeWindowRejectsEverything() {
        val now = 1_000_000L
        assertFalse(
            SignalingOfferFreshnessPolicy.isOfferFresh(
                timestampMillis = now - 1L,
                nowMillis = now,
                maxAgeMs = -1L,
            )
        )
    }

    @Test
    fun customWindowBoundaryIsInclusive() {
        val now = 1_000_000L
        assertTrue(
            SignalingOfferFreshnessPolicy.isOfferFresh(
                timestampMillis = now - 5_000L,
                nowMillis = now,
                maxAgeMs = 5_000L,
            )
        )
        assertFalse(
            SignalingOfferFreshnessPolicy.isOfferFresh(
                timestampMillis = now - 5_001L,
                nowMillis = now,
                maxAgeMs = 5_000L,
            )
        )
    }

    @Test
    fun shouldKeepOfferMatchesTypeCaseInsensitively() {
        val now = 1_000_000L
        assertTrue(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "OFFER",
                callId = "c1",
                terminatedCallIds = emptySet(),
                timestampMillis = now - 10_000L,
                nowMillis = now,
            )
        )
        assertTrue(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "Offer",
                callId = "c1",
                terminatedCallIds = emptySet(),
                timestampMillis = now - 10_000L,
                nowMillis = now,
            )
        )
    }

    @Test
    fun blankCallIdSkipsTerminatedCheck() {
        // 空 callId 不参与 terminated 集合比对：遗留行无 id 时走新鲜度判定。
        val now = 1_000_000L
        assertTrue(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "",
                terminatedCallIds = setOf(""),
                timestampMillis = now - 10_000L,
                nowMillis = now,
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

    @Test
    fun shouldKeepOfferHonorsCustomMaxAge() {
        val now = 1_000_000L
        assertTrue(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "c1",
                terminatedCallIds = emptySet(),
                timestampMillis = now - 4_000L,
                nowMillis = now,
                maxAgeMs = 5_000L,
            )
        )
        assertFalse(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "c1",
                terminatedCallIds = emptySet(),
                timestampMillis = now - 6_000L,
                nowMillis = now,
                maxAgeMs = 5_000L,
            )
        )
    }

    @Test
    fun terminatedCallIdMatchIsExact() {
        // terminated 集合按精确匹配：大小写不同不算命中。
        val now = 1_000_000L
        assertTrue(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "Call-A",
                terminatedCallIds = setOf("call-a"),
                timestampMillis = now - 1_000L,
                nowMillis = now,
            )
        )
    }

    @Test
    fun typeWithWhitespaceIsNotOffer() {
        // type 比对只忽略大小写不裁空白，衬垫过的 type 不是 offer。
        val now = 1_000_000L
        assertFalse(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = " offer ",
                callId = "c1",
                terminatedCallIds = emptySet(),
                timestampMillis = now - 1_000L,
                nowMillis = now,
            )
        )
    }

    @Test
    fun unrelatedCallIdIsKeptDespiteTerminatedSibling() {
        // 同一批轮询里兄弟 callId 被终止，不连累其他 callId 的新鲜 offer。
        val now = 1_000_000L
        assertTrue(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "c2",
                terminatedCallIds = setOf("c1"),
                timestampMillis = now - 1_000L,
                nowMillis = now,
            )
        )
    }

    @Test
    fun negativeTimestampKeptByKeepOffer() {
        val now = 1_000_000L
        assertTrue(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "c1",
                terminatedCallIds = emptySet(),
                timestampMillis = -5L,
                nowMillis = now,
            )
        )
    }

    @Test
    fun `offer 到达即时 age 为零被保留`() {
        // age = 0 走不到负分支，窗口判定含边界：刚落库的行必须可响铃。
        val now = 1_000_000L
        assertTrue(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "c1",
                terminatedCallIds = emptySet(),
                timestampMillis = now,
                nowMillis = now,
            )
        )
    }

    @Test
    fun `re-offer 前缀类型不是 offer 被过滤`() {
        // equals 只忽略大小写不做子串匹配："re-offer" 不是 offer。
        val now = 1_000_000L
        assertFalse(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "re-offer",
                callId = "c1",
                terminatedCallIds = emptySet(),
                timestampMillis = now - 1_000L,
                nowMillis = now,
            )
        )
    }

    @Test
    fun `已终止通话的 offer 即使时间戳未知也被丢弃`() {
        // terminated 检查排在新鲜度之前：挂断过的 callId 不会因时间戳未知被复活。
        val now = 1_000_000L
        assertFalse(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "c1",
                terminatedCallIds = setOf("c1"),
                timestampMillis = -5L,
                nowMillis = now,
            )
        )
    }

    @Test
    fun `同一新鲜 offer 重复轮询两次都被保留`() {
        // 新鲜度判定是无状态纯函数：重复轮询同一 offer 结果必须一致。
        val now = 1_000_000L
        repeat(2) {
            assertTrue(
                SignalingOfferFreshnessPolicy.shouldKeepOffer(
                    type = "offer",
                    callId = "c1",
                    terminatedCallIds = emptySet(),
                    timestampMillis = now - 1_000L,
                    nowMillis = now,
                )
            )
        }
    }

    @Test
    fun `切换账号后旧账号的 terminated 集合不再过滤同一 offer`() {
        // terminated 集合是调用方上下文：换账号后传入空集合，同一 offer 重新被保留。
        val now = 1_000_000L
        assertFalse(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "c1",
                terminatedCallIds = setOf("c1"),
                timestampMillis = now - 1_000L,
                nowMillis = now,
            )
        )
        assertTrue(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "c1",
                terminatedCallIds = emptySet(),
                timestampMillis = now - 1_000L,
                nowMillis = now,
            )
        )
    }

    @Test
    fun `未知时间戳压过零窗口`() {
        // 时间戳未知分支排在窗口判定之前：零/负窗口也挡不住未知时间戳的 offer。
        val now = 1_000_000L
        assertTrue(
            SignalingOfferFreshnessPolicy.isOfferFresh(
                timestampMillis = 0L,
                nowMillis = now,
                maxAgeMs = 0L,
            )
        )
        assertTrue(
            SignalingOfferFreshnessPolicy.isOfferFresh(
                timestampMillis = -5L,
                nowMillis = now,
                maxAgeMs = -1L,
            )
        )
    }

    @Test
    fun `未来时间戳遇零窗口仍被拒`() {
        // 窗口判定排在时钟偏移判定之前：窗口已关时未来时间戳也不放行。
        val now = 1_000_000L
        assertFalse(
            SignalingOfferFreshnessPolicy.isOfferFresh(
                timestampMillis = now + 1_000L,
                nowMillis = now,
                maxAgeMs = 0L,
            )
        )
    }

    @Test
    fun `空白 callId 仍走新鲜度且跳过 terminated 比对`() {
        val now = 1_000_000L
        assertFalse(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "   ",
                terminatedCallIds = setOf("   "),
                timestampMillis = now - IncomingCallCoordinator.STALE_MS - 5L,
                nowMillis = now,
            )
        )
        assertTrue(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "offer",
                callId = "   ",
                terminatedCallIds = setOf("   "),
                timestampMillis = now - 1_000L,
                nowMillis = now,
            )
        )
    }

    @Test
    fun `大写 OFFER 被终止集合照常过滤`() {
        val now = 1_000_000L
        assertFalse(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                type = "OFFER",
                callId = "c1",
                terminatedCallIds = setOf("c1"),
                timestampMillis = now - 1_000L,
                nowMillis = now,
            )
        )
    }

    @Test
    fun `自定义窗口下未来时间戳仍新鲜`() {
        val now = 1_000_000L
        assertTrue(
            SignalingOfferFreshnessPolicy.isOfferFresh(
                timestampMillis = now + 5_000L,
                nowMillis = now,
                maxAgeMs = 1_000L,
            )
        )
    }

}
