package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [MessageTimelineGroupingPolicy] (checklist line 931,
 * 40th block). Test-only, zero production changes.
 *
 * The oracle derives the wrapped difference independently of production: the
 * true gap is computed with [BigInteger] and truncated to its low 64 bits,
 * which is exactly the JVM `long` subtraction wrap — but the derivation path
 * never repeats the production expression. Two traps it nails down, both
 * verified offline before this file was written:
 * - the subtraction **wraps**: `(Long.MAX_VALUE, Long.MIN_VALUE)` has a true
 *   gap of 2^64-1 ms, yet the wrapped difference is -1, so the pair groups
 *   under the default 6-minute window — a naive unbounded-gap model says
 *   "not grouped" and is wrong (same for `(0, Long.MIN_VALUE)`, where the
 *   wrapped difference is exactly `Long.MIN_VALUE`);
 * - `abs(Long.MIN_VALUE)` stays negative: `Math.abs` returns
 *   `Long.MIN_VALUE` itself, so a wrapped difference of `Long.MIN_VALUE`
 *   groups even under window 0 or a negative window — only a window of
 *   `Long.MIN_VALUE` itself refuses it (`MIN < MIN` is false).
 * A Python mini-model differentially checks the oracle against both the
 * production expression and the test's BigInteger oracle re-run on the real
 * JVM (Java probe) on 5000 random inputs (0 diffs); 1576 of those inputs
 * overflow the subtraction and 801 make production disagree with the naive
 * unbounded-gap model.
 */
class MessageTimelineGroupingPolicyFuzzTest {

    private companion object {
        private const val RANDOM_ITERATIONS = 300

        private val EDGE_LONGS = longArrayOf(
            0L, 1L, -1L, 2L, -2L,
            359_999L, 360_000L, 360_001L, -359_999L, -360_000L, -360_001L,
            Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 1, Long.MIN_VALUE + 1,
            Long.MAX_VALUE - 359_999L, Long.MAX_VALUE - 360_000L, Long.MAX_VALUE - 360_001L,
            Long.MIN_VALUE + 359_999L, Long.MIN_VALUE + 360_000L, Long.MIN_VALUE + 360_001L,
            60_000L, -60_000L
        )

        private val EDGE_WINDOWS = longArrayOf(
            0L, 1L, -1L, 2L, -2L, 60_000L,
            359_999L, 360_000L, 360_001L,
            Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 1, Long.MIN_VALUE + 1
        )

        private val EDGE_EXTREMES = longArrayOf(
            Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 1, Long.MIN_VALUE + 1
        )
    }

    private fun message(id: String, senderId: String, timestamp: Long): Message =
        Message(id = id, chatId = "c1", senderId = senderId, content = "x", type = MessageType.TEXT, timestamp = timestamp)

    private fun oracleGrouped(a: Long, b: Long, windowMs: Long): Boolean {
        val wrapped = BigInteger.valueOf(a).subtract(BigInteger.valueOf(b)).toLong()
        val absDiff = if (wrapped == Long.MIN_VALUE) Long.MIN_VALUE else kotlin.math.abs(wrapped)
        return absDiff < windowMs
    }

    private fun naiveGrouped(a: Long, b: Long, windowMs: Long): Boolean {
        val gap = BigInteger.valueOf(a).subtract(BigInteger.valueOf(b)).abs()
        return gap < BigInteger.valueOf(windowMs)
    }

    private fun randomLong(random: Random): Long = when (random.nextInt(7)) {
        0 -> EDGE_LONGS[random.nextInt(EDGE_LONGS.size)]
        1 -> random.nextLong(-1_000_000L, 1_000_001L)
        2 -> random.nextLong()
        3 -> {
            val base = longArrayOf(0L, Long.MAX_VALUE, Long.MIN_VALUE, 360_000L, -360_000L)[random.nextInt(5)]
            base + random.nextLong(-400_000L, 400_001L)
        }
        4 -> EDGE_EXTREMES[random.nextInt(EDGE_EXTREMES.size)] + random.nextInt(-3, 4)
        5 -> random.nextLong()
        else -> random.nextLong(-1_000_000L, 1_000_001L)
    }

    private fun randomWindow(random: Random): Long = when (random.nextInt(4)) {
        0 -> EDGE_WINDOWS[random.nextInt(EDGE_WINDOWS.size)]
        1 -> SENDER_GROUP_WINDOW_MS
        2 -> random.nextLong(-1_000_000L, 1_000_001L)
        else -> random.nextLong(0L, 720_001L)
    }

    private fun assertGrouped(
        a: Long,
        b: Long,
        windowMs: Long,
        sameSender: Boolean,
        input: String
    ) {
        val expected = sameSender && oracleGrouped(a, b, windowMs)
        val sender = if (sameSender) "u1" else "u2"
        val actual = MessageTimelineGroupingPolicy.isGroupedWithPrevious(
            message("m2", "u1", a),
            message("m1", sender, b),
            windowMs = windowMs
        )
        assertEquals(expected, actual, "输入 $input：期望 $expected，实际 $actual")
    }

    @Test
    fun `random timestamps and windows match oracle`() {
        val random = Random(0x40_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val a = randomLong(random)
            val b = randomLong(random)
            val window = randomWindow(random)
            val sameSender = random.nextBoolean()
            assertGrouped(a, b, window, sameSender, "fuzz 迭代 #$i (a=$a, b=$b, window=$window, sameSender=$sameSender)")
        }
    }

    @Test
    fun `random near-boundary gaps match oracle`() {
        val random = Random(0x41_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val window = randomWindow(random)
            val base = randomLong(random)
            val offset = when (random.nextInt(6)) {
                0 -> window - 1
                1 -> window
                2 -> window + 1
                3 -> -(window - 1)
                4 -> -window
                else -> -(window + 1)
            }
            val a = base + offset
            val sameSender = random.nextBoolean()
            assertGrouped(a, base, window, sameSender, "边界 fuzz 迭代 #$i (base=$base, offset=$offset, window=$window, sameSender=$sameSender)")
        }
    }

    @Test
    fun `random overflow-prone pairs match oracle`() {
        val random = Random(0x42_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val a = EDGE_EXTREMES[random.nextInt(EDGE_EXTREMES.size)] + random.nextInt(-3, 4)
            val b = EDGE_EXTREMES[random.nextInt(EDGE_EXTREMES.size)] + random.nextInt(-3, 4)
            val window = randomWindow(random)
            val sameSender = random.nextBoolean()
            assertGrouped(a, b, window, sameSender, "溢出 fuzz 迭代 #$i (a=$a, b=$b, window=$window, sameSender=$sameSender)")
        }
    }

    @Test
    fun `subtraction overflow wraps into grouping`() {
        // 真实差距约 2^64 ms，回绕后的差值只有 -1，仍在 6 分钟窗内。
        assertTrue(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", Long.MAX_VALUE),
                message("m1", "u1", Long.MIN_VALUE)
            ),
            "(MAX, MIN) 回绕差值 -1，须成组"
        )
        assertTrue(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", Long.MIN_VALUE),
                message("m1", "u1", Long.MAX_VALUE)
            ),
            "(MIN, MAX) 回绕差值 1，须成组"
        )
        assertTrue(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", Long.MAX_VALUE),
                message("m1", "u1", -1L)
            ),
            "(MAX, -1) 回绕差值恰为 Long.MIN_VALUE，abs 后仍为负，须成组"
        )
        assertTrue(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", -1L),
                message("m1", "u1", Long.MAX_VALUE)
            ),
            "(-1, MAX) 回绕差值恰为 Long.MIN_VALUE，须成组"
        )
        assertTrue(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", Long.MAX_VALUE - 1),
                message("m1", "u1", Long.MIN_VALUE + 1)
            ),
            "(MAX-1, MIN+1) 回绕差值 -2，须成组"
        )
    }

    @Test
    fun `abs of Long MIN_VALUE stays negative`() {
        assertTrue(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", 0L),
                message("m1", "u1", Long.MIN_VALUE)
            ),
            "(0, MIN) 差值即 Long.MIN_VALUE，abs 不变仍为负，须成组"
        )
        assertTrue(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", Long.MIN_VALUE),
                message("m1", "u1", 0L)
            ),
            "(MIN, 0) 同理须成组"
        )
        // 窗口为 0 时普通零差距不成组，但回绕差值 Long.MIN_VALUE 仍小于 0。
        assertTrue(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", 0L),
                message("m1", "u1", Long.MIN_VALUE),
                windowMs = 0L
            ),
            "窗口 0 下 (0, MIN) 仍成组"
        )
        assertFalse(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", 0L),
                message("m1", "u1", 1L),
                windowMs = 0L
            ),
            "窗口 0 下差距 1 不成组"
        )
    }

    @Test
    fun `zero and negative windows`() {
        assertFalse(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", 1_000_000L),
                message("m1", "u1", 1_000_000L),
                windowMs = 0L
            ),
            "窗口 0 下零差距不成组"
        )
        assertFalse(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", 1_000_000L),
                message("m1", "u1", 1_000_000L),
                windowMs = -1L
            ),
            "负窗口下零差距不成组"
        )
        assertTrue(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", 0L),
                message("m1", "u1", Long.MIN_VALUE),
                windowMs = -1L
            ),
            "负窗口下 (0, MIN) 的 abs 仍为 Long.MIN_VALUE，小于 -1，须成组"
        )
        assertFalse(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", 0L),
                message("m1", "u1", Long.MIN_VALUE),
                windowMs = Long.MIN_VALUE
            ),
            "窗口本身就是 Long.MIN_VALUE 时 MIN < MIN 为假，不成组"
        )
        assertFalse(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", 1_000_001L),
                message("m1", "u1", 1_000_000L),
                windowMs = Long.MIN_VALUE
            ),
            "窗口 Long.MIN_VALUE 下任何差距都不成组"
        )
    }

    @Test
    fun `max window boundary`() {
        assertFalse(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", Long.MAX_VALUE),
                message("m1", "u1", 0L),
                windowMs = Long.MAX_VALUE
            ),
            "窗口 Long.MAX_VALUE 下差距恰为 Long.MAX_VALUE 时严格小于不成立"
        )
        assertTrue(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", Long.MAX_VALUE - 1),
                message("m1", "u1", 0L),
                windowMs = Long.MAX_VALUE
            ),
            "窗口 Long.MAX_VALUE 下差距 Long.MAX_VALUE - 1 成组"
        )
        assertTrue(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", 0L),
                message("m1", "u1", Long.MIN_VALUE),
                windowMs = Long.MAX_VALUE
            ),
            "窗口 Long.MAX_VALUE 下 (0, MIN) 的 abs 为负，须成组"
        )
    }

    @Test
    fun `window boundary is strict`() {
        assertTrue(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", 100L),
                message("m1", "u1", 100L),
                windowMs = 1L
            ),
            "窗口 1 下零差距成组"
        )
        assertFalse(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", 101L),
                message("m1", "u1", 100L),
                windowMs = 1L
            ),
            "窗口 1 下差距 1 恰为边界，不成组"
        )
        assertFalse(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", 99L),
                message("m1", "u1", 100L),
                windowMs = 1L
            ),
            "窗口 1 下反向差距 1 同样不成组"
        )
        assertTrue(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", 1_059_999L),
                message("m1", "u1", 1_000_000L),
                windowMs = 60_000L
            ),
            "窗口 60000 下差距 59999 成组"
        )
        assertFalse(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", 1_060_000L),
                message("m1", "u1", 1_000_000L),
                windowMs = 60_000L
            ),
            "窗口 60000 下差距恰 60000 不成组"
        )
    }

    @Test
    fun `near-overflow without wrap stays exact`() {
        assertTrue(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", Long.MAX_VALUE),
                message("m1", "u1", Long.MAX_VALUE - 359_999L)
            ),
            "(MAX, MAX-359999) 未溢出，差距 359999 成组"
        )
        assertFalse(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", Long.MAX_VALUE),
                message("m1", "u1", Long.MAX_VALUE - 360_000L)
            ),
            "(MAX, MAX-360000) 未溢出，差距恰为窗口，不成组"
        )
        assertFalse(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", Long.MAX_VALUE),
                message("m1", "u1", Long.MAX_VALUE - 360_001L)
            ),
            "(MAX, MAX-360001) 未溢出，差距超窗，不成组"
        )
        assertTrue(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", Long.MIN_VALUE),
                message("m1", "u1", Long.MIN_VALUE + 359_999L)
            ),
            "(MIN, MIN+359999) 未溢出，差距 359999 成组"
        )
        assertFalse(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", Long.MIN_VALUE),
                message("m1", "u1", Long.MIN_VALUE + 360_000L)
            ),
            "(MIN, MIN+360000) 未溢出，差距恰为窗口，不成组"
        )
        assertTrue(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", Long.MAX_VALUE),
                message("m1", "u1", Long.MAX_VALUE - 1)
            ),
            "(MAX, MAX-1) 差距 1 成组"
        )
    }

    @Test
    fun `sender mismatch dominates overflow`() {
        assertFalse(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", Long.MAX_VALUE),
                message("m1", "u2", Long.MIN_VALUE)
            ),
            "发送者不同时即使回绕差值极小也不成组"
        )
        assertFalse(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", 0L),
                message("m1", "u2", Long.MIN_VALUE)
            ),
            "发送者不同时 (0, MIN) 也不成组"
        )
        assertFalse(
            MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                message("m2", "u1", 1_000_000L),
                message("m1", "u2", 1_000_000L)
            ),
            "发送者不同时零差距也不成组"
        )
    }

    @Test
    fun `naive mathematical gap model disagrees on overflow pairs`() {
        // 按无界整数算差距会把这些对判为不成组，生产侧因回绕判为成组：
        // 钉住这组分歧，证明本文件不是在复述一个想当然的模型。
        val pairs = listOf(
            Long.MAX_VALUE to Long.MIN_VALUE,
            Long.MIN_VALUE to Long.MAX_VALUE,
            Long.MAX_VALUE to -1L,
            -1L to Long.MAX_VALUE,
            0L to Long.MIN_VALUE
        )
        for ((a, b) in pairs) {
            assertFalse(naiveGrouped(a, b, SENDER_GROUP_WINDOW_MS), "($a, $b) 的无界差距模型应判不成组")
            assertTrue(
                MessageTimelineGroupingPolicy.isGroupedWithPrevious(
                    message("m2", "u1", a),
                    message("m1", "u1", b)
                ),
                "($a, $b) 的生产侧因回绕应判成组"
            )
        }
    }
}
