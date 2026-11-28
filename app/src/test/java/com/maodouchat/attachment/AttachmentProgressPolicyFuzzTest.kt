package com.maodouchat.attachment

import com.maodouchat.core.model.AttachmentProgressPolicy
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [AttachmentProgressPolicy.mapSegmentProgress]
 * (checklist line 931, 38th block). Test-only, zero production changes.
 *
 * The oracle re-implements the mapping step by step in Double, mirroring
 * the production op order (Float segment width, Double ratio, Float
 * rounding of the result, Double delta check against `0.01f`). Two traps
 * it nails down, both verified offline before this file was written:
 * - the segment width is `end - start` in **Float**: for
 *   `(55, 100, 0.2f, 0.4f, 0.3f)` the true delta is 0.0099999927, below
 *   the `0.01f` threshold, so the update is suppressed — a naive Double
 *   model would wrongly expect `0.31f` to publish;
 * - a progress value just under `1.0` can still round to `1.0f`, and then
 *   `progress < 1f` fails so completion publishes despite a tiny delta
 *   (`(10^15 - 1, 10^15, 0f, 1f, 0.999f)` -> `1f`).
 * NaN is compared by category only: x86 float ops propagate the source
 * NaN payload, so two NaN results need not share bits.
 * A Python mini-model differentially checks the oracle against an
 * independent transcription of the production logic on 5000 random inputs
 * (0 diffs), and a Java probe re-running the same logic on the real JVM
 * agrees on the same 5000 inputs (0 diffs).
 */
class AttachmentProgressPolicyFuzzTest {

    private companion object {
        private const val RANDOM_ITERATIONS = 300

        private val EDGE_FLOATS = floatArrayOf(
            0f, -0.0f, 1f, -1f, 2f, -2f, 0.5f, 0.01f, 0.3f, 0.7f, 0.2f, 0.8f,
            0.99999994f, 1.0000001f, 1e-8f, 1e30f, -1e30f,
            Float.MIN_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE,
            Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN
        )

        private val EDGE_TOTALS = longArrayOf(
            1L, 2L, 3L, 7L, 10L, 100L, 999L, 1000L, 1_000_000L,
            1_000_000_000_000_000L, 1L shl 53, (1L shl 53) + 1, 1L shl 62,
            Long.MAX_VALUE, Long.MAX_VALUE - 1, 0L, -1L, Long.MIN_VALUE
        )
    }

    private fun oracleProgress(
        completed: Long,
        total: Long,
        start: Float,
        end: Float,
        previousPublished: Float
    ): Float? {
        if (total <= 0L) return null
        val startClamped = start.coerceIn(0f, 1f)
        val endClamped = end.coerceIn(startClamped, 1f)
        val ratio = (completed.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
        val width = endClamped - startClamped
        val value = (startClamped.toDouble() + ratio * width.toDouble()).coerceIn(0.0, 1.0)
        val progress = value.toFloat()
        val prev = if (previousPublished < 0f) -1f else previousPublished.coerceIn(0f, 1f)
        // 0.01f widened to Double (0.009999999776482582), mirroring MIN_PUBLISH_DELTA.
        if (prev >= 0f && abs(prev.toDouble() - value) < 0.01f.toDouble() && progress < 1f) {
            return null
        }
        return progress
    }

    private fun assertSameProgress(expected: Float?, actual: Float?, input: String) {
        if (expected == null) {
            assertNull(actual, "输入 $input：期望 null，实际 $actual")
            return
        }
        assertTrue(actual != null, "输入 $input：期望 $expected，实际 null")
        if (expected.isNaN()) {
            assertTrue(actual!!.isNaN(), "输入 $input：期望 NaN，实际 $actual")
            return
        }
        assertEquals(expected, actual!!, 0f, "输入 $input：期望 $expected，实际 $actual")
    }

    private fun randomFloat(random: Random): Float = when (random.nextInt(5)) {
        0 -> EDGE_FLOATS[random.nextInt(EDGE_FLOATS.size)]
        1 -> random.nextFloat() * 4f - 2f
        2 -> random.nextFloat()
        3 -> Float.fromBits(random.nextInt())
        else -> random.nextFloat() * 0.02f + 0.99f
    }

    private fun randomCompleted(random: Random, total: Long): Long = when (random.nextInt(5)) {
        0 -> longArrayOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)[random.nextInt(5)]
        1 -> random.nextLong(-1000L, 2001L)
        2 -> total + random.nextLong(-5L, 6L) // total 近 Long 上限时加法回绕仍是合法输入
        3 -> (random.nextDouble(-1.0, 2.0) * total.toDouble()).toLong()
        else -> random.nextLong()
    }

    @Test
    fun `random small inputs match oracle`() {
        val random = Random(0x38_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val total = random.nextLong(1L, 10001L)
            val completed = random.nextLong(-total, total * 2 + 1)
            val start = random.nextFloat() * 4f - 2f
            val end = random.nextFloat() * 4f - 2f
            val prev = random.nextFloat() * 4f - 2f
            val input = "($completed, $total, $start, $end, $prev)"
            assertSameProgress(
                oracleProgress(completed, total, start, end, prev),
                AttachmentProgressPolicy.mapSegmentProgress(completed, total, start, end, prev),
                "fuzz 迭代 #$i $input"
            )
        }
    }

    @Test
    fun `random edge magnitudes match oracle`() {
        val random = Random(0x39_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val total = EDGE_TOTALS[random.nextInt(EDGE_TOTALS.size)]
            val completed = randomCompleted(random, total)
            val start = randomFloat(random)
            val end = randomFloat(random)
            val prev = randomFloat(random)
            val input = "($completed, $total, $start, $end, $prev)"
            assertSameProgress(
                oracleProgress(completed, total, start, end, prev),
                AttachmentProgressPolicy.mapSegmentProgress(completed, total, start, end, prev),
                "fuzz 迭代 #$i $input"
            )
        }
    }

    @Test
    fun `random near boundary previous values match oracle`() {
        val random = Random(0x3A_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val total = longArrayOf(1L, 2L, 3L, 7L, 100L, 999L, 1000L, 1_000_000L)[random.nextInt(8)]
            val completed = random.nextLong(0L, total + 1)
            val start = random.nextFloat()
            val end = start + random.nextFloat() * (1f - start)
            val published = oracleProgress(completed, total, start, end, -1f)!!
            val prev = when (random.nextInt(6)) {
                0 -> published
                1 -> published + 0.005f
                2 -> published - 0.005f
                3 -> published + 0.02f
                4 -> published - 0.02f
                else -> random.nextFloat()
            }
            val input = "($completed, $total, $start, $end, $prev)"
            assertSameProgress(
                oracleProgress(completed, total, start, end, prev),
                AttachmentProgressPolicy.mapSegmentProgress(completed, total, start, end, prev),
                "fuzz 迭代 #$i $input"
            )
        }
    }

    @Test
    fun `invalid total always yields null`() {
        val random = Random(0x3B_2026)
        repeat(100) { i ->
            val total = random.nextLong(Long.MIN_VALUE, 1L)
            val completed = random.nextLong()
            val start = randomFloat(random)
            val end = randomFloat(random)
            val prev = randomFloat(random)
            assertNull(
                AttachmentProgressPolicy.mapSegmentProgress(completed, total, start, end, prev),
                "fuzz 迭代 #$i：total=$total 非正必须返回 null"
            )
        }
        assertNull(AttachmentProgressPolicy.mapSegmentProgress(10, 0, 0f, 0.5f, -1f))
        assertNull(AttachmentProgressPolicy.mapSegmentProgress(10, Long.MIN_VALUE, 0f, 0.5f, -1f))
        assertNull(AttachmentProgressPolicy.mapSegmentProgress(10, 0, Float.NaN, 1f, -1f))
    }

    @Test
    fun `nan inputs stay nan or pass through`() {
        val nanStart = AttachmentProgressPolicy.mapSegmentProgress(50, 100, Float.NaN, 1f, -1f)
        assertTrue(nanStart != null && nanStart.isNaN(), "NaN start 必须返回 NaN，实际 $nanStart")
        val nanEnd = AttachmentProgressPolicy.mapSegmentProgress(50, 100, 0f, Float.NaN, -1f)
        assertTrue(nanEnd != null && nanEnd.isNaN(), "NaN end 必须返回 NaN，实际 $nanEnd")
        // NaN previous：不是负数故不算未设置，但 `prev >= 0f` 为假，永不抑制。
        assertEquals(
            0.5f,
            AttachmentProgressPolicy.mapSegmentProgress(50, 100, 0f, 1f, Float.NaN)!!, 0f
        )
        assertEquals(
            0.505f,
            AttachmentProgressPolicy.mapSegmentProgress(505, 1000, 0f, 1f, Float.NaN)!!, 0f
        )
        assertNull(AttachmentProgressPolicy.mapSegmentProgress(10, 0, Float.NaN, 1f, -1f))
    }

    @Test
    fun `infinities clamp into range`() {
        assertEquals(
            1f,
            AttachmentProgressPolicy.mapSegmentProgress(50, 100, Float.POSITIVE_INFINITY, 2f, -1f)!!, 0f
        )
        assertEquals(
            0.5f,
            AttachmentProgressPolicy.mapSegmentProgress(50, 100, Float.NEGATIVE_INFINITY, 1f, -1f)!!, 0f
        )
        assertEquals(
            0.5f,
            AttachmentProgressPolicy.mapSegmentProgress(50, 100, 0f, Float.POSITIVE_INFINITY, -1f)!!, 0f
        )
        // +Inf previous 钳制到 1f：与 0.995 的差 0.005 小于阈值，抑制。
        assertNull(AttachmentProgressPolicy.mapSegmentProgress(995, 1000, 0f, 1f, Float.POSITIVE_INFINITY))
        // -Inf previous 是负数，算未设置。
        assertEquals(
            0.1f,
            AttachmentProgressPolicy.mapSegmentProgress(10, 100, 0f, 1f, Float.NEGATIVE_INFINITY)!!, 0f
        )
    }

    @Test
    fun `completion always publishes even with tiny delta`() {
        assertEquals(
            1f,
            AttachmentProgressPolicy.mapSegmentProgress(100, 100, 0f, 1f, 0.99f)!!, 0f
        )
        // progressValue = 0.999999999999999，Float 舍入后恰为 1.0f，`progress < 1f`
        // 为假，纵使与 0.999f 的差远小于阈值也照发。
        assertEquals(
            1f,
            AttachmentProgressPolicy.mapSegmentProgress(
                999_999_999_999_999L, 1_000_000_000_000_000L, 0f, 1f, 0.999f
            )!!, 0f
        )
        // 段终点不到 1 不强制发布：与 0.795f 的差 0.005 小于阈值，抑制。
        assertNull(AttachmentProgressPolicy.mapSegmentProgress(100, 100, 0.2f, 0.8f, 0.795f))
        assertEquals(
            0.8f,
            AttachmentProgressPolicy.mapSegmentProgress(100, 100, 0.2f, 0.8f, 0.7f)!!, 0f
        )
        // 0.999999 仍在 1.0f 的半个 Float 间距之外，不舍入到 1f，照常抑制。
        assertNull(AttachmentProgressPolicy.mapSegmentProgress(999999, 1000000, 0f, 1f, 0.99f))
        assertNull(
            AttachmentProgressPolicy.mapSegmentProgress(99999994, 100000000, 0f, 1f, 0.99999994f)
        )
    }

    @Test
    fun `threshold delta boundary pins float width trap`() {
        // 0.51f - 0.5f 的 Double 差 0.010000000000000009，大于阈值，发布。
        assertEquals(
            0.51f,
            AttachmentProgressPolicy.mapSegmentProgress(510, 1000, 0f, 1f, 0.5f)!!, 0f
        )
        assertNull(AttachmentProgressPolicy.mapSegmentProgress(509, 1000, 0f, 1f, 0.5f))
        assertNull(AttachmentProgressPolicy.mapSegmentProgress(505, 1000, 0f, 1f, 0.5f))
        // 窄段陷阱：段宽先在 Float 里算（0.4f - 0.2f = 0.2f），55% 处真值
        // 0.3100000046，与 0.3f 的差 0.0099999927 小于阈值，抑制；若把段宽
        // 当 Double 算会误判为 0.31f 发布。
        assertNull(AttachmentProgressPolicy.mapSegmentProgress(55, 100, 0.2f, 0.4f, 0.3f))
        assertEquals(
            0.32f,
            AttachmentProgressPolicy.mapSegmentProgress(60, 100, 0.2f, 0.4f, 0.3f)!!, 0f
        )
    }

    @Test
    fun `negative zero previous is set not unset`() {
        assertNull(AttachmentProgressPolicy.mapSegmentProgress(1, 10000, 0f, 1f, -0.0f))
        assertEquals(
            0.0001f,
            AttachmentProgressPolicy.mapSegmentProgress(1, 10000, 0f, 1f, -1f)!!, 0f
        )
    }

    @Test
    fun `huge totals near long max`() {
        assertEquals(
            1f,
            AttachmentProgressPolicy.mapSegmentProgress(Long.MAX_VALUE, Long.MAX_VALUE, 0f, 1f, -1f)!!, 0f
        )
        // Long.MAX_VALUE - 1 转 Double 后与 Long.MAX_VALUE 相等，ratio 仍为 1.0。
        assertEquals(
            1f,
            AttachmentProgressPolicy.mapSegmentProgress(
                Long.MAX_VALUE - 1, Long.MAX_VALUE, 0f, 1f, -1f
            )!!, 0f
        )
        assertEquals(
            0.5f,
            AttachmentProgressPolicy.mapSegmentProgress(
                Long.MAX_VALUE / 2, Long.MAX_VALUE, 0f, 1f, -1f
            )!!, 0f
        )
        assertEquals(
            0f,
            AttachmentProgressPolicy.mapSegmentProgress(Long.MIN_VALUE, Long.MAX_VALUE, 0f, 1f, -1f)!!, 0f
        )
    }

    @Test
    fun `reversed and degenerate segments pin to start`() {
        assertEquals(
            0.8f,
            AttachmentProgressPolicy.mapSegmentProgress(50, 100, 0.8f, 0.2f, -1f)!!, 0f
        )
        assertEquals(
            0.8f,
            AttachmentProgressPolicy.mapSegmentProgress(100, 100, 0.8f, 0.2f, -1f)!!, 0f
        )
        assertEquals(
            0.3f,
            AttachmentProgressPolicy.mapSegmentProgress(50, 100, 0.3f, 0.3f, -1f)!!, 0f
        )
        assertEquals(
            0f,
            AttachmentProgressPolicy.mapSegmentProgress(50, 100, -0.5f, -0.2f, -1f)!!, 0f
        )
        assertEquals(
            1f,
            AttachmentProgressPolicy.mapSegmentProgress(50, 100, 1.5f, 2f, -1f)!!, 0f
        )
        assertEquals(
            0.5f,
            AttachmentProgressPolicy.mapSegmentProgress(50, 100, -0.5f, 1.5f, -1f)!!, 0f
        )
        assertEquals(
            0.5f,
            AttachmentProgressPolicy.mapSegmentProgress(50, 100, Float.MIN_VALUE, 1f, -1f)!!, 0f
        )
        assertEquals(
            0f,
            AttachmentProgressPolicy.mapSegmentProgress(1, 100, 0f, Float.MIN_VALUE, -1f)!!, 0f
        )
    }
}
