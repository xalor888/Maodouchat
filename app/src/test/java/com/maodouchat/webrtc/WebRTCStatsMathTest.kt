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
    fun `large counts do not overflow`() {
        // lost * 100 若先按 Long 算会溢出；实现必须先转 Double
        val big = 100_000_000_000L
        val pct = packetLossPercent(big, big)!!
        assertEquals(50.0, pct, 1e-9)
        assertTrue("结果应在 0..100 之间", pct in 0.0..100.0)
    }

    @Test
    fun `result is always within zero and one hundred for non negative inputs`() {
        listOf(0L to 1L, 1L to 0L, 3L to 7L, 999L to 1L, 1L to 999L).forEach { (lost, received) ->
            val pct = packetLossPercent(lost, received)!!
            assertTrue("$lost/$received 越界: $pct", pct in 0.0..100.0)
        }
    }
}
