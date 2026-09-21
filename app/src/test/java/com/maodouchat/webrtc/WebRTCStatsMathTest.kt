package com.maodouchat.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G176：`WebRTCStatsMath` 两个纯函数的测试
 * （G176 刚从 WebRTCManager 的成员方法抽出来）。
 */
class WebRTCStatsMathTest {

    // ─── readStatNumber ───

    @Test
    fun `numbers convert to double`() {
        assertEquals(1.0, readStatNumber(1))
        assertEquals(42L.toDouble(), readStatNumber(42L))
        assertEquals(3.5, readStatNumber(3.5))
        assertEquals(2.5f.toDouble(), readStatNumber(2.5f))
        assertEquals(0.0, readStatNumber(0))
    }

    @Test
    fun `parseable strings convert`() {
        assertEquals(1.5, readStatNumber("1.5"))
        assertEquals(120.0, readStatNumber("120"))
        assertEquals(-3.25, readStatNumber("-3.25"))
    }

    @Test
    fun `unparseable strings and other types are null`() {
        assertNull(readStatNumber("abc"))
        assertNull(readStatNumber(""))
        assertNull(readStatNumber("1.2.3"))
        assertNull(readStatNumber(null))
        assertNull(readStatNumber(true))
        assertNull(readStatNumber(listOf(1, 2)))
        assertNull(readStatNumber(mapOf("a" to 1)))
    }

    // ─── packetLossPercent ───

    @Test
    fun `no samples means null not zero`() {
        // 没有样本时不该显示「0% 丢包」——那会让用户以为网络很好
        assertNull(packetLossPercent(0L, 0L))
    }

    @Test
    fun `nothing lost is zero percent`() {
        assertEquals(0.0, packetLossPercent(0L, 100L)!!, 1e-9)
    }

    @Test
    fun `everything lost is one hundred percent`() {
        assertEquals(100.0, packetLossPercent(100L, 0L)!!, 1e-9)
    }

    @Test
    fun `typical ratios`() {
        assertEquals(1.0, packetLossPercent(1L, 99L)!!, 1e-9)
        assertEquals(10.0, packetLossPercent(10L, 90L)!!, 1e-9)
        assertEquals(50.0, packetLossPercent(50L, 50L)!!, 1e-9)
        assertEquals(25.0, packetLossPercent(250L, 750L)!!, 1e-9)
    }

    @Test
    fun `very large but realistic counts stay exact and in range`() {
        // 真实通话里丢包数是百万级；这里放到千亿级验证精度与范围。
        // 注意：Long 溢出要 lost > 9.2e16 才会发生，那不是真实场景，
        // 所以这条**不**声称覆盖溢出——覆盖溢出见下面那条。
        val big = 100_000_000_000L
        val pct = packetLossPercent(big, big)!!
        assertEquals(50.0, pct, 1e-9)
        assertTrue("结果应在 0..100 之间", pct in 0.0..100.0)
    }

    @Test
    fun `absurd counts still yield a sane percentage instead of overflowing`() {
        // lost * 100 若先按 Long 算，超过 Long.MAX_VALUE/100 ≈ 9.2e16 就回绕成负数，
        // 于是「几乎全丢」会被算成负百分比。实现必须先转 Double 再乘。
        val huge = Long.MAX_VALUE / 10
        val pct = packetLossPercent(huge, huge)!!
        assertTrue("回绕成负数了: $pct", pct >= 0.0)
        assertEquals(50.0, pct, 1e-6)
    }

    @Test
    fun `result is always within zero and one hundred for non negative inputs`() {
        listOf(0L to 1L, 1L to 0L, 3L to 7L, 999L to 1L, 1L to 999L).forEach { (lost, received) ->
            val pct = packetLossPercent(lost, received)!!
            assertTrue("$lost/$received 越界: $pct", pct in 0.0..100.0)
        }
    }
}
