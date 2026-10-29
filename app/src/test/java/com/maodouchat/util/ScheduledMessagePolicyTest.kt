package com.maodouchat.util

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScheduledMessagePolicyTest {

    private val now = 1_700_000_000_000L

    @Test
    fun limitsAreAsDocumented() {
        assertEquals(60_000L, ScheduledMessagePolicy.MIN_DELAY_MS, "最短延迟应为 1 分钟")
        assertEquals(7L * 24 * 60 * 60 * 1000, ScheduledMessagePolicy.MAX_DELAY_MS, "最长应为 7 天")
        assertEquals(4000, ScheduledMessagePolicy.MAX_TEXT_LENGTH)
        assertEquals(56, ScheduledMessagePolicy.MAX_PENDING_PER_CHAT)
    }

    @Test
    fun clampedSendAtIsAlwaysValid() {
        // 遍历各种畸形/边界输入：夹完之后必须落在 [MIN, MAX] 延迟窗内
        val extremes = listOf(
            Long.MIN_VALUE, -1L, 0L, 1L,
            now - 10L * 24 * 60 * 60 * 1000, // 十天前
            now - 1L,                        // 刚刚过去
            now,                             // 就是现在
            now + ScheduledMessagePolicy.MIN_DELAY_MS - 1, // 差 1ms 太早
            now + ScheduledMessagePolicy.MIN_DELAY_MS,     // 正好下界
            now + ScheduledMessagePolicy.MAX_DELAY_MS,     // 正好上界
            now + ScheduledMessagePolicy.MAX_DELAY_MS + 1, // 差 1ms 太晚
            now + 100L * 24 * 60 * 60 * 1000,              // 100 天后
            Long.MAX_VALUE,
        )
        extremes.forEach { raw ->
            val clamped = ScheduledMessagePolicy.clampSendAt(raw, now)
            assertTrue(
                ScheduledMessagePolicy.isValidSendAt(clamped, now),
                "clampSendAt($raw) = $clamped 未通过 isValidSendAt",
            )
        }
    }

    @Test
    fun clampBoundsAreInclusiveAndExclusiveCorrectly() {
        // 下界：正好 now+60s 应原样保留（不提前也不推后）
        val lo = now + ScheduledMessagePolicy.MIN_DELAY_MS
        assertEquals(lo, ScheduledMessagePolicy.clampSendAt(lo, now), "正好下界不应被推动")
        // 上界同理
        val hi = now + ScheduledMessagePolicy.MAX_DELAY_MS
        assertEquals(hi, ScheduledMessagePolicy.clampSendAt(hi, now), "正好上界不应被推动")
        // 太早被推到下界，太晚被压到上界
        assertEquals(lo, ScheduledMessagePolicy.clampSendAt(now + 1L, now))
        assertEquals(hi, ScheduledMessagePolicy.clampSendAt(now + ScheduledMessagePolicy.MAX_DELAY_MS * 2, now))
    }

    @Test
    fun isValidSendAtChecksTheDelayWindowNotAbsoluteTime() {
        assertFalse(ScheduledMessagePolicy.isValidSendAt(now + 59_999L, now), "差 1ms 不到 1 分钟应判否")
        assertTrue(ScheduledMessagePolicy.isValidSendAt(now + 60_000L, now), "正好 1 分钟应判是")
        assertTrue(ScheduledMessagePolicy.isValidSendAt(now + ScheduledMessagePolicy.MAX_DELAY_MS, now))
        assertFalse(ScheduledMessagePolicy.isValidSendAt(now + ScheduledMessagePolicy.MAX_DELAY_MS + 1, now), "超过 7 天应判否")
        assertFalse(ScheduledMessagePolicy.isValidSendAt(now - 1L, now), "过去应判否")
    }

    @Test
    fun quickDelaysAreAllValidAndAscending() {
        val steps = ScheduledMessagePolicy.QUICK_DELAYS_MS
        // 快捷档位必须本身合法——否则用户点一下就得到一个会被判否的 sendAt
        steps.forEach { d ->
            assertTrue(
                d in ScheduledMessagePolicy.MIN_DELAY_MS..ScheduledMessagePolicy.MAX_DELAY_MS,
                "快捷档位 $d 不在合法窗口内",
            )
        }
        // UI 按数组顺序渲染，必须是升序（否则时间选择器看起来是乱的）
        assertEquals(steps.sorted(), steps, "快捷档位必须升序")
        // 首尾对应最短/最长
        assertEquals(ScheduledMessagePolicy.MIN_DELAY_MS, steps.first(), "第一个档位应是最短延迟")
        assertEquals(ScheduledMessagePolicy.MAX_DELAY_MS, steps.last(), "最后一个档位应是最长延迟")
    }

    @Test
    fun textNormalizationTrimsAndTruncates() {
        assertEquals("hello", ScheduledMessagePolicy.normalizeText("  hello  "))
        assertEquals("", ScheduledMessagePolicy.normalizeText(null))
        assertEquals("", ScheduledMessagePolicy.normalizeText("   "))
        val long = ScheduledMessagePolicy.normalizeText("x".repeat(9_000))
        assertEquals(ScheduledMessagePolicy.MAX_TEXT_LENGTH, long.length, "应截到 4000 字")
    }

    @Test
    fun validTextMeansNonBlankAfterNormalization() {
        assertTrue(ScheduledMessagePolicy.isValidText("hi"))
        // 纯空白归一化成空串 → 判否
        assertFalse(ScheduledMessagePolicy.isValidText("   "), "纯空白必须判否")
        assertFalse(ScheduledMessagePolicy.isValidText(""))
        assertFalse(ScheduledMessagePolicy.isValidText(null))
        // 超长文本先被截到 4000，因此仍判有效——这是设计（截断而不是拒绝）
        assertTrue(ScheduledMessagePolicy.isValidText("x".repeat(50_000)), "超长应被截断后判有效")
    }

    @Test
    fun canAddMoreStopsAtTheCap() {
        assertTrue(ScheduledMessagePolicy.canAddMore(0))
        assertTrue(ScheduledMessagePolicy.canAddMore(55))
        assertFalse(ScheduledMessagePolicy.canAddMore(56), "满 56 条必须拒绝")
        assertFalse(ScheduledMessagePolicy.canAddMore(100))
    }

    @Test
    fun delayFromNowNeverGoesNegative() {
        assertEquals(0L, ScheduledMessagePolicy.delayFromNow(now - 5_000L, now), "过去的时间延迟应为 0 不是负数")
        assertEquals(0L, ScheduledMessagePolicy.delayFromNow(now, now))
        assertEquals(5_000L, ScheduledMessagePolicy.delayFromNow(now + 5_000L, now))
    }

    @Test
    fun quickDelaysMatchReminderPresetsExactly() {
        // 两个列表 14 档逐项相等：UI 上提醒与定时用的快捷档位应当一致
        assertEquals(
            MessageReminderPolicy.QUICK_DELAYS_MS,
            ScheduledMessagePolicy.QUICK_DELAYS_MS,
        )
    }

    @Test
    fun normalizeTextPreservesInnerWhitespace() {
        assertEquals("a  b", ScheduledMessagePolicy.normalizeText(" a  b "))
        assertEquals("a\tb", ScheduledMessagePolicy.normalizeText("\ta\tb\t"))
    }

    @Test
    fun exactly4000CharsKeptAnd4001Truncated() {
        val keep = "x".repeat(ScheduledMessagePolicy.MAX_TEXT_LENGTH)
        assertEquals(keep, ScheduledMessagePolicy.normalizeText(keep))
        assertEquals(
            ScheduledMessagePolicy.MAX_TEXT_LENGTH,
            ScheduledMessagePolicy.normalizeText(keep + "x").length,
        )
    }

    @Test
    fun negativePendingCountCountsAsNotFull() {
        // canAddMore 只看 < 56：负数也放行（调用方不应传负数，语义钉住）
        assertTrue(ScheduledMessagePolicy.canAddMore(-1))
    }

    @Test
    fun delayFromNowWithMaxTimestampDoesNotOverflow() {
        assertEquals(Long.MAX_VALUE - now, ScheduledMessagePolicy.delayFromNow(Long.MAX_VALUE, now))
    }

    @Test
    fun clampSendAtIsIdempotent() {
        val raws = listOf(
            Long.MIN_VALUE, -1L, 0L, now - 30L * 24 * 60 * 60 * 1000,
            now, now + 30_000L, now + ScheduledMessagePolicy.MAX_DELAY_MS,
            now + 100L * 24 * 60 * 60 * 1000, Long.MAX_VALUE,
        )
        raws.forEach { raw ->
            val once = ScheduledMessagePolicy.clampSendAt(raw, now)
            assertEquals(once, ScheduledMessagePolicy.clampSendAt(once, now), "clamp 应幂等：$raw")
        }
    }

    @Test
    fun normalizeTextTruncationKeepsPrefix() {
        val kept = "x".repeat(ScheduledMessagePolicy.MAX_TEXT_LENGTH)
        assertEquals(kept, ScheduledMessagePolicy.normalizeText(kept + "TAIL"))
        assertFalse(ScheduledMessagePolicy.normalizeText(kept + "TAIL").endsWith("TAIL"))
    }

    @Test
    fun delayFromNowIsExactAtWindowEndpoints() {
        assertEquals(
            ScheduledMessagePolicy.MIN_DELAY_MS,
            ScheduledMessagePolicy.delayFromNow(now + ScheduledMessagePolicy.MIN_DELAY_MS, now),
        )
        assertEquals(
            ScheduledMessagePolicy.MAX_DELAY_MS,
            ScheduledMessagePolicy.delayFromNow(now + ScheduledMessagePolicy.MAX_DELAY_MS, now),
        )
    }

    @Test
    fun validTextRejectsLongWhitespaceOnlyInput() {
        assertFalse(ScheduledMessagePolicy.isValidText(" ".repeat(5_000)))
        assertFalse(ScheduledMessagePolicy.isValidText("\t\n ".repeat(100)))
    }

    @Test
    fun normalizeTextTrimsNewlinesAndTabsAtEdges() {
        assertEquals("hello", ScheduledMessagePolicy.normalizeText("\n\t hello \n"))
        assertEquals("hi\nthere", ScheduledMessagePolicy.normalizeText("\n  hi\nthere  \t"))
    }

    @Test
    fun quickDelaysExactFourteenStepsPinned() {
        assertEquals(
            listOf(
                60_000L, 5 * 60_000L, 15 * 60_000L, 30 * 60_000L,
                60 * 60_000L, 2 * 60 * 60_000L, 3 * 60 * 60_000L,
                4 * 60 * 60_000L, 6 * 60 * 60_000L, 12 * 60 * 60_000L,
                24 * 60 * 60_000L, 2L * 24 * 60 * 60_000L,
                3L * 24 * 60 * 60_000L, 7L * 24 * 60 * 60_000L,
            ),
            ScheduledMessagePolicy.QUICK_DELAYS_MS,
        )
    }

    @Test
    fun normalizeTextTrimsBeforeTruncating() {
        assertEquals("y".repeat(4000), ScheduledMessagePolicy.normalizeText("  " + "y".repeat(4010) + "  "))
        assertEquals("z".repeat(4000), ScheduledMessagePolicy.normalizeText(" " + "z".repeat(4000)))
    }

    @Test
    fun isValidTextAcceptsExactly4000AfterTrim() {
        assertTrue(ScheduledMessagePolicy.isValidText(" " + "x".repeat(4000) + " "))
        assertTrue(ScheduledMessagePolicy.isValidText("x".repeat(4001)))
    }

    @Test
    fun clampSendAtKeepsInteriorValuesUnchanged() {
        assertEquals(now + 61_000L, ScheduledMessagePolicy.clampSendAt(now + 61_000L, now))
        assertEquals(now + 3L * 24 * 60 * 60 * 1000, ScheduledMessagePolicy.clampSendAt(now + 3L * 24 * 60 * 60 * 1000, now))
        assertEquals(
            now + ScheduledMessagePolicy.MAX_DELAY_MS - 1,
            ScheduledMessagePolicy.clampSendAt(now + ScheduledMessagePolicy.MAX_DELAY_MS - 1, now),
        )
    }

    @Test
    fun isValidSendAtRejectsZeroAndNegativeDelay() {
        assertFalse(ScheduledMessagePolicy.isValidSendAt(now, now))
        assertFalse(ScheduledMessagePolicy.isValidSendAt(now - 60_000L, now))
        assertFalse(ScheduledMessagePolicy.isValidSendAt(now - ScheduledMessagePolicy.MAX_DELAY_MS, now))
    }
}
