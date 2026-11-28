package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [RecordingWaveformPolicy] (checklist line 931,
 * 39th block). Test-only, zero production changes.
 *
 * The oracle re-implements `resample` / `barHeightFraction` step by step in
 * Float, mirroring the production op order (Float index math, Float boost
 * factors, `coerceIn` clamps). Two traps it nails down, both verified offline
 * before this file was written:
 * - the resample index is computed in **Float**: for
 *   `(barCount=12, size=100, i=3)` the exact index is 26.999... but the
 *   Float computation yields 27, so bar 3 samples `waveform[27]`, not
 *   `waveform[26]` — a naive Double model would sample the wrong element
 *   (same for `(11, 91, 7)` -> 63);
 * - `barHeightFraction` differs from a Double model by 1 ulp at
 *   `h = Float.fromBits(0x3eb35d76)`: production gives
 *   `Float.fromBits(0x3edb4802)`, the Double model
 *   `Float.fromBits(0x3edb4801)`.
 * NaN is compared by category only: x86 float ops propagate the source NaN
 * payload, so two NaN results need not share bits. `maxOf` on JVM is
 * `Math.max` (verified against the Kotlin 2.4.0 stdlib bytecode, the
 * project's Kotlin version), so NaN propagates through the live-boost tail:
 * a NaN base value or a NaN live amplitude makes the tail bar NaN.
 * A Python mini-model differentially checks the oracle against an
 * independent transcription of the production logic on 5000 random inputs
 * (0 diffs), and a Java probe re-running the same logic on the real JVM
 * agrees on the same 5000 inputs (0 diffs).
 */
class RecordingWaveformPolicyFuzzTest {

    private companion object {
        private const val RANDOM_ITERATIONS = 300

        private val EDGE_FLOATS = floatArrayOf(
            0f, -0.0f, 1f, -1f, 2f, -2f, 0.5f, 0.01f, 0.3f, 0.7f, 0.2f, 0.8f,
            0.99999994f, 1.0000001f, 1e-8f, 1e30f, -1e30f,
            Float.MIN_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE,
            Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN
        )

        private val EDGE_BAR_COUNTS = intArrayOf(0, 1, 2, 3, 4, 5, 31, 32, 33, 64, 100)
    }

    private fun oracleResample(
        waveform: List<Float>,
        liveAmplitude: Float,
        barCount: Int
    ): List<Float> {
        val base = if (waveform.isEmpty()) {
            List(barCount) { 0.08f }
        } else {
            List(barCount) { i ->
                val idx = ((i.toFloat() / (barCount - 1).coerceAtLeast(1)) *
                    (waveform.size - 1).coerceAtLeast(0)).toInt()
                    .coerceIn(0, waveform.lastIndex.coerceAtLeast(0))
                waveform.getOrElse(idx) { 0f }.coerceIn(0f, 1f)
            }
        }
        return base.mapIndexed { i, v ->
            if (i >= barCount - 3) {
                maxOf(v, liveAmplitude * (0.7f + 0.1f * (i - (barCount - 3))))
            } else {
                v
            }
        }
    }

    private fun oracleBarHeight(h: Float): Float =
        (0.12f + h * 0.88f).coerceIn(0.12f, 1f)

    private fun assertSameBars(expected: List<Float>, actual: List<Float>, input: String) {
        assertEquals(expected.size, actual.size, "输入 $input：条数不一致")
        expected.zip(actual).forEachIndexed { i, (e, a) ->
            if (e.isNaN()) {
                assertTrue(a.isNaN(), "输入 $input：bar[$i] 期望 NaN，实际 $a")
            } else {
                assertEquals(e, a, 0f, "输入 $input：bar[$i] 期望 $e，实际 $a")
            }
        }
    }

    private fun assertSameHeight(expected: Float, actual: Float, input: String) {
        if (expected.isNaN()) {
            assertTrue(actual.isNaN(), "输入 $input：期望 NaN，实际 $actual")
        } else {
            assertEquals(expected, actual, 0f, "输入 $input：期望 $expected，实际 $actual")
        }
    }

    private fun randomFloat(random: Random): Float = when (random.nextInt(6)) {
        0 -> EDGE_FLOATS[random.nextInt(EDGE_FLOATS.size)]
        1 -> random.nextFloat() * 4f - 2f
        2 -> random.nextFloat()
        3 -> Float.fromBits(random.nextInt())
        4 -> random.nextFloat() * 0.02f + 0.99f
        else -> random.nextFloat() * 0.002f - 0.001f
    }

    private fun randomWaveform(random: Random, size: Int): List<Float> =
        List(size) { randomFloat(random) }

    @Test
    fun `random resample matches oracle`() {
        val random = Random(0x39_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val barCount = when (random.nextInt(3)) {
                0 -> EDGE_BAR_COUNTS[random.nextInt(EDGE_BAR_COUNTS.size)]
                1 -> random.nextInt(0, 65)
                else -> random.nextInt(28, 37)
            }
            val waveform = randomWaveform(random, random.nextInt(0, 41))
            val live = randomFloat(random)
            val input = "(size=${waveform.size}, live=$live, barCount=$barCount)"
            assertSameBars(
                oracleResample(waveform, live, barCount),
                RecordingWaveformPolicy.resample(waveform, live, barCount),
                "fuzz 迭代 #$i $input"
            )
        }
    }

    @Test
    fun `random edge magnitudes match oracle`() {
        val random = Random(0x3A_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val barCount = EDGE_BAR_COUNTS[random.nextInt(EDGE_BAR_COUNTS.size)]
            val size = intArrayOf(0, 1, 2, 3, 5, 32, 100)[random.nextInt(7)]
            val waveform = List(size) { EDGE_FLOATS[random.nextInt(EDGE_FLOATS.size)] }
            val live = EDGE_FLOATS[random.nextInt(EDGE_FLOATS.size)]
            val input = "(size=$size, live=$live, barCount=$barCount)"
            assertSameBars(
                oracleResample(waveform, live, barCount),
                RecordingWaveformPolicy.resample(waveform, live, barCount),
                "fuzz 迭代 #$i $input"
            )
        }
    }

    @Test
    fun `random bar height matches oracle`() {
        val random = Random(0x3B_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val h = when (random.nextInt(5)) {
                0 -> randomFloat(random)
                1 -> 0.12f + random.nextFloat() * 0.002f - 0.001f
                2 -> 1f + random.nextFloat() * 0.002f - 0.001f
                3 -> random.nextFloat() * 0.002f - 0.001f
                else -> EDGE_FLOATS[random.nextInt(EDGE_FLOATS.size)]
            }
            assertSameHeight(
                oracleBarHeight(h),
                RecordingWaveformPolicy.barHeightFraction(h),
                "fuzz 迭代 #$i h=$h"
            )
        }
    }

    @Test
    fun `index rounding trap pins float sampling`() {
        // i=3 的精确下标是 26.999...，Float 算出来恰为 27，采 waveform[27]。
        val wave100 = List(100) { it / 100f }
        val bars12 = RecordingWaveformPolicy.resample(wave100, 0f, 12)
        assertEquals(0.27f, bars12[3], 0f)
        assertEquals(0.54f, bars12[6], 0f)
        // (barCount=11, size=91, i=7)：精确 62.999...，Float 得 63。
        val wave91 = List(91) { it / 100f }
        val bars11 = RecordingWaveformPolicy.resample(wave91, 0f, 11)
        assertEquals(0.63f, bars11[7], 0f)
    }

    @Test
    fun `bar height one ulp trap pins float arithmetic`() {
        val h = Float.fromBits(0x3eb35d76)
        val production = RecordingWaveformPolicy.barHeightFraction(h)
        assertEquals(Float.fromBits(0x3edb4802), production, 0f)
        // 按 Double 算再转回 Float 会差 1 ulp：生产侧是 Float 运算，不是 Double。
        val doubleModel = ((0.12 + h.toDouble() * 0.88).coerceIn(0.12, 1.0)).toFloat()
        assertEquals(Float.fromBits(0x3edb4801), doubleModel, 0f)
        assertNotEquals(doubleModel, production)
    }

    @Test
    fun `nan propagates through maxOf tail`() {
        val withNan = RecordingWaveformPolicy.resample(listOf(0.1f, Float.NaN, 0.3f), 0.5f)
        assertTrue(withNan[16].isNaN(), "bar[16] 采 NaN 样本，非尾条也须是 NaN")
        assertTrue(withNan[29].isNaN(), "bar[29] 采 NaN 样本，maxOf 传播 NaN")
        assertTrue(withNan[30].isNaN(), "bar[30] 采 NaN 样本，maxOf 传播 NaN")
        assertEquals(0.45f, withNan[31], 0f)
        val nanLive = RecordingWaveformPolicy.resample(listOf(0.1f, 0.2f), Float.NaN)
        assertTrue(nanLive.dropLast(3).none { it.isNaN() }, "非尾条不受 live NaN 影响")
        assertTrue(nanLive.takeLast(3).all { it.isNaN() }, "live NaN 时尾三条全是 NaN")
        assertEquals(0.1f, nanLive[0], 0f)
        val nanEmpty = RecordingWaveformPolicy.resample(emptyList(), Float.NaN)
        assertTrue(nanEmpty.dropLast(3).all { it == 0.08f }, "空波形非尾条仍是 0.08")
        assertTrue(nanEmpty.takeLast(3).all { it.isNaN() }, "空波形 + live NaN 时尾三条是 NaN")
        assertTrue(RecordingWaveformPolicy.barHeightFraction(Float.NaN).isNaN())
    }

    @Test
    fun `infinities clamp and boost`() {
        val bars = RecordingWaveformPolicy.resample(
            listOf(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY), 0f
        )
        assertTrue(bars.all { it == 0f || it == 1f }, "±Inf 样本须钳制到 0/1，实际 $bars")
        val infLive = RecordingWaveformPolicy.resample(listOf(0.1f), Float.POSITIVE_INFINITY)
        assertTrue(infLive.takeLast(3).all { it == Float.POSITIVE_INFINITY })
        val negInfLive = RecordingWaveformPolicy.resample(listOf(0.1f), Float.NEGATIVE_INFINITY)
        assertTrue(negInfLive.takeLast(3).all { it == 0.1f }, "live -Inf 不抬升尾条")
        assertEquals(1f, RecordingWaveformPolicy.barHeightFraction(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0.12f, RecordingWaveformPolicy.barHeightFraction(Float.NEGATIVE_INFINITY), 0f)
    }

    @Test
    fun `empty waveform and small bar counts`() {
        assertEquals(emptyList<Float>(), RecordingWaveformPolicy.resample(listOf(0.5f), 0f, 0))
        assertEquals(emptyList<Float>(), RecordingWaveformPolicy.resample(emptyList(), 0f, 0))
        assertEquals(listOf(0.9f), RecordingWaveformPolicy.resample(listOf(0.5f), 1f, 1))
        assertEquals(listOf(0.9f), RecordingWaveformPolicy.resample(emptyList(), 1f, 1))
        assertEquals(
            listOf(0.8f, 0.9f),
            RecordingWaveformPolicy.resample(listOf(0.5f), 1f, 2)
        )
        assertEquals(
            listOf(0.7f, 0.8f, 0.9f),
            RecordingWaveformPolicy.resample(listOf(0.5f), 1f, 3)
        )
        assertEquals(
            listOf(0.35f, 0.4f, 0.45f),
            RecordingWaveformPolicy.resample(listOf(0.1f), 0.5f).takeLast(3)
        )
    }

    @Test
    fun `negative bar count throws`() {
        assertFailsWith<IllegalArgumentException> {
            RecordingWaveformPolicy.resample(listOf(0.5f), 0f, -1)
        }
        assertFailsWith<IllegalArgumentException> {
            RecordingWaveformPolicy.resample(emptyList(), 0f, -1)
        }
        assertFailsWith<IllegalArgumentException> {
            RecordingWaveformPolicy.resample(listOf(0.5f), 0f, Int.MIN_VALUE)
        }
    }

    @Test
    fun `negative zero and subnormal inputs`() {
        val bars = RecordingWaveformPolicy.resample(listOf(-0.0f), 0f)
        assertEquals(
            (-0.0f).toRawBits(), bars[0].toRawBits(),
            "非尾条保留 -0.0f 的符号位"
        )
        assertEquals(
            0.0f.toRawBits(), bars[31].toRawBits(),
            "尾条 maxOf(-0.0f, 0.0f) 得 +0.0f"
        )
        val sub = RecordingWaveformPolicy.resample(listOf(Float.MIN_VALUE), 0f)
        assertTrue(sub.dropLast(3).all { it == Float.MIN_VALUE }, "次正规数不被钳制")
        assertEquals(
            0.12f,
            RecordingWaveformPolicy.barHeightFraction(Float.MIN_VALUE), 0f
        )
        assertEquals(0.12f, RecordingWaveformPolicy.barHeightFraction(-0.0f), 0f)
    }

    @Test
    fun `live boost never lowers bars`() {
        val low = RecordingWaveformPolicy.resample(listOf(0.9f), 0.2f)
        assertTrue(low.takeLast(3).all { it == 0.9f }, "live 低于基值时尾条保持原值")
        val mid = RecordingWaveformPolicy.resample(listOf(0.1f), 0.5f)
        assertEquals(0.35f, mid[29], 0f)
        assertEquals(0.4f, mid[30], 0f)
        assertEquals(0.45f, mid[31], 0f)
        val negative = RecordingWaveformPolicy.resample(listOf(0.1f), -1f)
        assertTrue(negative.takeLast(3).all { it == 0.1f }, "负 live 不抬升尾条")
    }
}
