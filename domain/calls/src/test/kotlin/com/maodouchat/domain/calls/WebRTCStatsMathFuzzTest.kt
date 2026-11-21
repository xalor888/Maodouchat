package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WebRTCStatsMath 两个纯函数 fuzz（清单 931 行第十五块，簇 3）。
 *
 * 源码实测的语义：`packetLossPercent` 先转 Double 再除（`lost * 100` 不走 Long，
 * 故大数不回绕），`lost + received <= 0`（含 Long 加法回绕成负）返 null；
 * `readStatNumber` 只认 Number（`toDouble()`）与可解析字符串（`toDoubleOrNull()`），其余 null。
 */
class WebRTCStatsMathFuzzTest {

    private companion object {
        private const val RANDOM_ITERATIONS = 200
    }

    private fun assertDoubleOrNullEquals(expected: Double?, actual: Double?, message: String) {
        if (expected == null) {
            assertNull(actual, message)
            return
        }
        assertNotNull(actual, message)
        if (expected.isNaN()) {
            assertTrue(actual.isNaN(), "$message：期望 NaN，实际 $actual")
        } else {
            assertEquals(expected, actual, 0.0, message)
        }
    }

    private fun packetLossOracle(lost: Long, received: Long): Double? {
        val total = lost + received
        if (total <= 0L) return null
        return lost.toDouble() * 100.0 / total.toDouble()
    }

    @Test
    fun `packetLossPercentMatchesOracle`() {
        val random = Random(0x15_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val lost = random.nextLong(-100, 1_000_000_000_001L)
            val received = random.nextLong(-100, 1_000_000_000_001L)
            assertDoubleOrNullEquals(
                packetLossOracle(lost, received), packetLossPercent(lost, received),
                "fuzz 迭代 #$i：packetLossPercent($lost, $received) 必须与公式 oracle 全等"
            )
        }
    }

    @Test
    fun `packetLossPercentStaysInRangeAndHandlesBoundaries`() {
        // 钉住边界：无样本 / 加法回绕 / 负数总量一律 null，不是 0%。
        assertNull(packetLossPercent(0L, 0L), "0/0 无样本必须 null")
        assertNull(packetLossPercent(Long.MAX_VALUE, 1L), "总量回绕成负必须 null")
        assertNull(packetLossPercent(Long.MAX_VALUE, Long.MAX_VALUE), "双 MAX 回绕必须 null")
        assertNull(packetLossPercent(-10L, 5L), "负总量必须 null")
        assertNull(packetLossPercent(0L, -1L), "负总量必须 null")
        assertNull(packetLossPercent(-5L, -5L), "负总量必须 null")
        // 非负输入恒在 0..100；两端点钉住。
        val random = Random(0x15_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val lost = random.nextLong(0, 1_000_000_000_001L)
            val received = random.nextLong(0, 1_000_000_000_001L)
            val pct = packetLossPercent(lost, received)
            if (lost == 0L && received == 0L) {
                assertNull(pct, "fuzz 迭代 #$i：0/0 必须 null")
            } else {
                assertNotNull(pct, "fuzz 迭代 #$i：非负且有样本不得 null")
                assertTrue(pct in 0.0..100.0, "fuzz 迭代 #$i：$lost/$received 越界：$pct")
            }
        }
        assertEquals(0.0, packetLossPercent(0L, 100L)!!, 0.0, "全未丢必须是 0%")
        assertEquals(100.0, packetLossPercent(100L, 0L)!!, 0.0, "全丢必须是 100%")
        assertEquals(50.0, packetLossPercent(Long.MAX_VALUE / 10, Long.MAX_VALUE / 10)!!, 1e-6, "千亿级对半丢必须是 50%")
    }

    @Test
    fun `packetLossPercentIsMonotoneInEachArgument`() {
        val random = Random(0x15_2026)
        repeat(100) { i ->
            val received = random.nextLong(1, 10_001L)
            var previous: Double? = null
            repeat(20) { step ->
                val lost = step * 500L
                val pct = packetLossPercent(lost, received)!!
                if (previous != null) {
                    assertTrue(pct >= previous, "fuzz 迭代 #$i：固定 received=$received 时丢包率不得随 lost 下降")
                }
                previous = pct
            }
            val lost = random.nextLong(1, 10_001L)
            var previousDown: Double? = null
            repeat(20) { step ->
                val receivedGrowing = step * 500L + 1L
                val pct = packetLossPercent(lost, receivedGrowing)!!
                if (previousDown != null) {
                    assertTrue(pct <= previousDown, "fuzz 迭代 #$i：固定 lost=$lost 时丢包率不得随 received 上升")
                }
                previousDown = pct
            }
        }
    }

    @Test
    fun `readStatNumberMatchesTypeTable`() {
        val random = Random(0x15_2026)
        val numericStrings = listOf("1.5", "120", "-3.25", "1e3", "2.5E2", "1E-2", " 1.5 ", "42", "NaN", "Infinity", "-Infinity")
        val garbageStrings = listOf("abc", "", "1.2.3", "0x10", "1,000", "--1", "1..2", "null", "true")
        repeat(RANDOM_ITERATIONS) { i ->
            val value: Any? = when (random.nextInt(0, 6)) {
                0 -> random.nextInt(-1_000_000, 1_000_001)
                1 -> random.nextLong(-1_000_000_000_000L, 1_000_000_000_001L)
                2 -> random.nextDouble(-1_000.0, 1_000.0)
                3 -> numericStrings[random.nextInt(numericStrings.size)]
                4 -> garbageStrings[random.nextInt(garbageStrings.size)]
                else -> listOf(null, true, false, listOf(1, 2), mapOf("a" to 1))[random.nextInt(5)]
            }
            val expected: Double? = when (value) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull()
                else -> null
            }
            assertDoubleOrNullEquals(
                expected, readStatNumber(value),
                "fuzz 迭代 #$i：readStatNumber($value) 必须与类型分发表全等"
            )
        }
    }

    @Test
    fun `readStatNumberNeverThrowsOnRandomInput`() {
        val random = Random(0x15_2026)
        val fragments = listOf("1", ".", "e", "E", "-", "+", " ", "x", "NaN", "inf", "0", "9", ",", "_")
        repeat(RANDOM_ITERATIONS) { i ->
            val value: Any? = when (random.nextInt(0, 4)) {
                0 -> buildString { repeat(random.nextInt(0, 12)) { append(fragments[random.nextInt(fragments.size)]) } }
                1 -> random.nextDouble() * 1e308 * (if (random.nextBoolean()) 1 else -1)
                2 -> random.nextLong()
                else -> null
            }
            // 只断言不抛：任何输入都不得把异常漏给调用方。
            readStatNumber(value)
            packetLossPercent(random.nextLong(), random.nextLong())
        }
    }
}
