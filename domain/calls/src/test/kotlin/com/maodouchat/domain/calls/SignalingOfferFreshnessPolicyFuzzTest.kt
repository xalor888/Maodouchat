package com.maodouchat.domain.calls

import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignalingOfferFreshnessPolicyFuzzTest {

    private val random = Random(0x0FFE_2026L)

    // 安全域：|值| < 2^62，now - timestamp 不会回绕，
    // 此时 BigInteger 精确算术与 Long 算术一致，可作独立 oracle。
    private val safeBound = 1L shl 62

    private fun randomSafeLong(): Long = random.nextLong(-safeBound, safeBound)

    private fun bigFreshOracle(timestampMillis: Long, nowMillis: Long, maxAgeMs: Long): Boolean {
        if (timestampMillis <= 0L) return true
        if (maxAgeMs <= 0L) return false
        val age = BigInteger.valueOf(nowMillis).subtract(BigInteger.valueOf(timestampMillis))
        if (age < BigInteger.ZERO) return true
        return age <= BigInteger.valueOf(maxAgeMs)
    }

    @Test
    fun `isOfferFresh matches BigInteger oracle in safe range`() {
        val maxAges = listOf(0L, 1L, 1_000L, 120_000L, Long.MAX_VALUE, 7_000L)
        repeat(200) {
            val timestamp = randomSafeLong()
            val now = randomSafeLong()
            val maxAge = maxAges.random(random)
            assertEquals(
                bigFreshOracle(timestamp, now, maxAge),
                SignalingOfferFreshnessPolicy.isOfferFresh(timestamp, now, maxAge),
                "timestamp=$timestamp now=$now maxAge=$maxAge",
            )
        }
    }

    @Test
    fun `wraparound behavior is pinned as-is`() {
        // age = MIN - 1 回绕为 MAX：数学真值是“未来时间戳→放行”，
        // 但回绕后 age 为极大正数、超出窗口，生产实现返回 false；
        // 钉住当前行为，不当 bug 修（由 Python 迷你模型事先算出）。
        assertFalse(SignalingOfferFreshnessPolicy.isOfferFresh(1L, Long.MIN_VALUE))
        // age = MIN - MAX 回绕为 1：数学真值同样是未来→放行，回绕后仍放行。
        assertTrue(SignalingOfferFreshnessPolicy.isOfferFresh(Long.MAX_VALUE, Long.MIN_VALUE))
        // timestamp <= 0 的分支先于一切：未知时间戳恒放行。
        assertTrue(SignalingOfferFreshnessPolicy.isOfferFresh(Long.MIN_VALUE, Long.MAX_VALUE))
    }

    @Test
    fun `age boundary is inclusive`() {
        // age 恰等于 maxAge 放行，maxAge + 1 拒绝
        assertTrue(SignalingOfferFreshnessPolicy.isOfferFresh(1_000L, 121_000L, 120_000L))
        assertFalse(SignalingOfferFreshnessPolicy.isOfferFresh(1_000L, 121_001L, 120_000L))
        // 未来时间戳（时钟偏差）恒放行
        assertTrue(SignalingOfferFreshnessPolicy.isOfferFresh(5_000L, 1_000L, 1_000L))
        // maxAge <= 0 时正时间戳恒拒绝，但未知时间戳仍放行
        assertFalse(SignalingOfferFreshnessPolicy.isOfferFresh(1_000L, 1_000L, 0L))
        assertFalse(SignalingOfferFreshnessPolicy.isOfferFresh(1_000L, 1_000L, -1L))
        assertTrue(SignalingOfferFreshnessPolicy.isOfferFresh(0L, 99L, -5L))
    }

    private val typePool = listOf(
        "offer", "OFFER", "Offer", "oFfEr", "offers", "offer ", " offer",
        "", "audio", "answer", "candidate",
    )

    private fun randomCallId(): String {
        val alphabet = "abcXYZ019_- "
        val length = random.nextInt(0, 8)
        return buildString {
            repeat(length) { append(alphabet.random(random)) }
        }
    }

    // shouldKeepOffer 的逐字复述：type 须精确等于 offer（忽略大小写）；
    // 非空白 callId 在终止集里则拒绝；其余走 isOfferFresh。
    private fun oracleShouldKeepOffer(
        type: String,
        callId: String,
        terminatedCallIds: Set<String>,
        timestampMillis: Long,
        nowMillis: Long,
        maxAgeMs: Long,
    ): Boolean {
        if (!type.equals("offer", ignoreCase = true)) return false
        if (callId.isNotBlank() && callId in terminatedCallIds) return false
        return bigFreshOracle(timestampMillis, nowMillis, maxAgeMs)
    }

    @Test
    fun `shouldKeepOffer matches composed oracle over random inputs`() {
        repeat(200) {
            val type = typePool.random(random)
            val callId = randomCallId()
            val terminated = buildSet {
                repeat(random.nextInt(0, 4)) { add(randomCallId()) }
                if (random.nextInt(4) == 0) add(callId)
            }
            val timestamp = randomSafeLong()
            val now = randomSafeLong()
            val maxAge = listOf(0L, 1_000L, 120_000L, Long.MAX_VALUE).random(random)
            assertEquals(
                oracleShouldKeepOffer(type, callId, terminated, timestamp, now, maxAge),
                SignalingOfferFreshnessPolicy.shouldKeepOffer(
                    type, callId, terminated, timestamp, now, maxAge,
                ),
                "type=$type callId=$callId terminated=$terminated " +
                    "timestamp=$timestamp now=$now maxAge=$maxAge",
            )
        }
    }

    @Test
    fun `type matching is case-insensitive but exact`() {
        val accepted = listOf("offer", "OFFER", "Offer", "oFfEr")
        val rejected = listOf("offers", "offer ", " offer", "", "audio", "answer")
        for (type in accepted) {
            assertTrue(
                SignalingOfferFreshnessPolicy.shouldKeepOffer(type, "c1", emptySet(), 1_000L, 1_000L),
                "type=$type",
            )
        }
        for (type in rejected) {
            assertFalse(
                SignalingOfferFreshnessPolicy.shouldKeepOffer(type, "c1", emptySet(), 1_000L, 1_000L),
                "type=$type",
            )
        }
    }

    @Test
    fun `blank callId skips the terminated set`() {
        val terminated = setOf("", "   ", "c1")
        for (callId in listOf("", "   ")) {
            assertTrue(
                SignalingOfferFreshnessPolicy.shouldKeepOffer(
                    "offer", callId, terminated, 1_000L, 1_000L,
                ),
                "callId='$callId'",
            )
        }
        assertFalse(
            SignalingOfferFreshnessPolicy.shouldKeepOffer(
                "offer", "c1", terminated, 1_000L, 1_000L,
            ),
        )
    }
}
