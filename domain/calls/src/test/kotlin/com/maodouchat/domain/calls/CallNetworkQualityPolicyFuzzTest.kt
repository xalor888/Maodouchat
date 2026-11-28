package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 通话链路质量分级 fuzz（清单 931 行第二十一块）。
 * 只加测试、零生产改动：`CallNetworkQualityPolicy.fromStats`。
 * 固定种子，同一份输入每次跑出同一批用例。
 */
class CallNetworkQualityPolicyFuzzTest {

    // 与生产侧逐字同构的 oracle：分级表按 rtt/loss 的阈值逐项判定。
    private fun expectedQuality(rttMs: Long?, packetLossPercent: Double?): NetworkQuality {
        if (rttMs == null) return NetworkQuality.UNKNOWN
        val loss = packetLossPercent
        return when {
            loss != null && rttMs <= 200 && loss <= 1.0 -> NetworkQuality.GOOD
            loss != null && rttMs <= 500 && loss <= 4.0 -> NetworkQuality.FAIR
            loss == null && rttMs <= 200 -> NetworkQuality.GOOD
            loss == null && rttMs <= 500 -> NetworkQuality.FAIR
            else -> NetworkQuality.POOR
        }
    }

    private fun randomRtt(r: Random): Long? =
        if (r.nextInt(10) == 0) null else r.nextLong(-1000L, 5001L)

    private fun randomLoss(r: Random): Double? =
        if (r.nextInt(10) == 0) null else r.nextDouble(-5.0, 110.0)

    @Test
    fun `fromStats matches bucket table on random inputs`() {
        val r = Random(0x9E7A_0621L)
        repeat(300) {
            val rtt = randomRtt(r)
            val loss = randomLoss(r)
            assertEquals(expectedQuality(rtt, loss), CallNetworkQualityPolicy.fromStats(rtt, loss))
        }
    }

    @Test
    fun `bucket boundaries are pinned`() {
        val rtts = listOf(199L, 200L, 201L, 499L, 500L, 501L)
        val losses = listOf<Double?>(0.999999, 1.0, 1.000001, 3.999999, 4.0, 4.000001, null)
        for (rtt in rtts) {
            for (loss in losses) {
                assertEquals(expectedQuality(rtt, loss), CallNetworkQualityPolicy.fromStats(rtt, loss))
            }
        }
        // 四个角钉住。
        assertEquals(NetworkQuality.GOOD, CallNetworkQualityPolicy.fromStats(200, 1.0))
        assertEquals(NetworkQuality.FAIR, CallNetworkQualityPolicy.fromStats(201, 1.0))
        assertEquals(NetworkQuality.FAIR, CallNetworkQualityPolicy.fromStats(500, 4.0))
        assertEquals(NetworkQuality.POOR, CallNetworkQualityPolicy.fromStats(501, 4.0))
        assertEquals(NetworkQuality.GOOD, CallNetworkQualityPolicy.fromStats(200, null))
        assertEquals(NetworkQuality.FAIR, CallNetworkQualityPolicy.fromStats(500, null))
        assertEquals(NetworkQuality.POOR, CallNetworkQualityPolicy.fromStats(501, null))
        // 负值不触发任何下限：rtt/loss 再小也只看上限。
        assertEquals(NetworkQuality.GOOD, CallNetworkQualityPolicy.fromStats(-1000, -5.0))
        assertEquals(NetworkQuality.GOOD, CallNetworkQualityPolicy.fromStats(0, 0.0))
    }

    @Test
    fun `null rtt is always unknown`() {
        val r = Random(0xBADC_0621L)
        repeat(200) {
            val loss = when (r.nextInt(5)) {
                0 -> null
                1 -> Double.NaN
                2 -> Double.POSITIVE_INFINITY
                3 -> Double.NEGATIVE_INFINITY
                else -> randomLoss(r)
            }
            assertEquals(NetworkQuality.UNKNOWN, CallNetworkQualityPolicy.fromStats(null, loss))
        }
    }

    @Test
    fun `nan and infinite loss fall through to poor`() {
        for (rtt in listOf(0L, 200L, 500L)) {
            assertEquals(NetworkQuality.POOR, CallNetworkQualityPolicy.fromStats(rtt, Double.NaN))
            assertEquals(NetworkQuality.POOR, CallNetworkQualityPolicy.fromStats(rtt, Double.POSITIVE_INFINITY))
            // -Inf 小于一切阈值：只看 rtt 分级。
            val expected = if (rtt <= 200) NetworkQuality.GOOD else NetworkQuality.FAIR
            assertEquals(expected, CallNetworkQualityPolicy.fromStats(rtt, Double.NEGATIVE_INFINITY))
        }
    }
}
