package com.maodouchat.util

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G211b：`ScheduledMessagePolicy` —— 定时发送。
 *
 * KDoc 写着「纯函数」，被 7 个文件引用，零测试。
 * 这里最值钱的一条不是「记住几个例子」，而是一个**往返不变式**：
 *
 * > `clampSendAt` 的输出对**任何**输入都必须让 `isValidSendAt` 成立。
 *
 * 为什么重要：`clampSendAt` 是写入前的最后一道关。它若漏放某种畸形输入
 * （负数、0、`Long.MAX_VALUE`、过去时间、正好在边界外 1ms），
 * 那条待发消息就成了一个 `isValidSendAt` 判否的脏行——
 * Worker 到点可能立刻发、或永远不发。
 */
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
        // 我第一版这里写的 assertTrue（还标注「见下方说明」），**错了**：
        // "   " 归一化成 "" → isNotEmpty() 为 false → 判否。实现是对的。
        assertFalse(ScheduledMessagePolicy.isValidText("   "), "纯空白必须判否")
        assertFalse(ScheduledMessagePolicy.isValidText(""))
        assertFalse(ScheduledMessagePolicy.isValidText(null))
        // 超长文本先被截到 4000，因此仍判有效——这是设计（截断而不是拒绝）
        assertTrue(ScheduledMessagePolicy.isValidText("x".repeat(50_000)), "超长应被截断后判有效")
    }

    /**
     * 顺带记一个**观察**（不写断言，因为它恒真）：`isValidText` 里的
     * `t.length <= MAX_TEXT_LENGTH` 永远不会为假——`t` 已经由 `normalizeText`
     * 截到 `MAX_TEXT_LENGTH` 了。这是个死条件，不是 bug（语义正好等价于
     * `normalizeText(raw).isNotEmpty()`），但它读起来像在防超长，实际不防。
     * 已在测试里用「超长文本判有效」把真实语义钉住，免得后人误以为它会拒绝超长。
     */
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
}
