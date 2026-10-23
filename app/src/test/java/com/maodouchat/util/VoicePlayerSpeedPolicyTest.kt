package com.maodouchat.util

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `VoicePlayer` 倍速档位环：`nextSpeed` / `formatSpeedLabel` 纯函数钉住。
 */
class VoicePlayerSpeedPolicyTest {

    private val steps = VoicePlayer.SPEED_STEPS.toList()

    @Test
    fun speedStepsAreTheExpectedRing() {
        // 把顺序钉住：改顺序就是改用户体验，而且会让下面几条一起失效
        assertEquals(listOf(1f, 1.5f, 2f, 0.5f), steps, "SPEED_STEPS 的顺序变了")
    }

    @Test
    fun nextSpeedWalksTheRingInOrder() {
        assertEquals(1.5f, VoicePlayer.nextSpeed(1f))
        assertEquals(2f, VoicePlayer.nextSpeed(1.5f))
        assertEquals(0.5f, VoicePlayer.nextSpeed(2f), "2x 之后应绕到最慢档（顺序设计如此）")
        assertEquals(1f, VoicePlayer.nextSpeed(0.5f), "0.5x 之后应回到 1x，闭合整圈")
    }

    @Test
    fun nextSpeedReturnsToStartAfterAFullRing() {
        // 环的闭合性：转 len 次必须回到原点
        steps.forEach { start ->
            var cur = start
            repeat(steps.size) { cur = VoicePlayer.nextSpeed(cur) }
            assertEquals(start, cur, "从 $start 出发转 ${steps.size} 次应回到起点，实际 $cur")
        }
    }

    @Test
    fun unknownSpeedAdvancesFromTheImplicitFirstStep() {
        // 未知值按 idx=0 处理再 +1，所以落到 steps[1]=1.5f 而不是 1f
        listOf(0.75f, 3f, 0f, -1f, 1.25f, 100f).forEach { unknown ->
            assertEquals(1.5f, VoicePlayer.nextSpeed(unknown), "未知倍速 $unknown 应从隐含第一档往前一格")
        }
    }

    @Test
    fun unknownSpeedIsNeverReturnedAsIs() {
        // 兜底不能让垃圾值继续流传：返回值必须仍是档位表成员
        listOf(0.75f, 3f, 0f, -1f, 1.25f, 100f).forEach { unknown ->
            val out = VoicePlayer.nextSpeed(unknown)
            assertTrue(out in steps, "nextSpeed($unknown)=$out 必须落在档位表内")
        }
    }

    @Test
    fun nextSpeedAlwaysReturnsAKnownStep() {
        // 任意输入（包括垃圾值）的返回值必须仍是档位表里的一员
        listOf(-1f, 0f, 0.25f, 0.5f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 99f, Float.MAX_VALUE)
            .forEach { input ->
                val out = VoicePlayer.nextSpeed(input)
                assertTrue(out in steps, "nextSpeed($input)=$out 不在档位表 $steps 内")
            }
    }

    @Test
    fun formatSpeedLabelMatchesEveryStep() {
        assertEquals("0.5x", VoicePlayer.formatSpeedLabel(0.5f))
        assertEquals("1x", VoicePlayer.formatSpeedLabel(1f))
        assertEquals("1.5x", VoicePlayer.formatSpeedLabel(1.5f))
        assertEquals("2x", VoicePlayer.formatSpeedLabel(2f))
        // 未知值 → "1x"（与 nextSpeed 的回落一致）
        assertEquals("1x", VoicePlayer.formatSpeedLabel(7f))
    }

    @Test
    fun everyStepHasALabelAndEveryLabelRoundTrips() {
        // 档位 ↔ 文案必须一一对应：不能让某个档位显示成另一档的文案
        steps.forEach { step ->
            val label = VoicePlayer.formatSpeedLabel(step)
            assertTrue(label.endsWith("x"), "$step 的文案应形如 'Nx'，实际 $label")
            assertTrue(label != "1x" || step == 1f, "$step 不应显示成 1x")
        }
        // 四个档位对应四个不同文案
        assertEquals(steps.size, steps.map { VoicePlayer.formatSpeedLabel(it) }.distinct().size,
            "四个档位应有四个不同文案")
    }

    @Test
    fun nextSpeedAndLabelsAgreeForEveryReachableState() {
        // 从任一起点出发走几步，文案始终与状态一致
        var cur = 1f
        repeat(steps.size * 2) {
            cur = VoicePlayer.nextSpeed(cur)
            val label = VoicePlayer.formatSpeedLabel(cur)
            assertTrue(
                label == "0.5x" || label == "1x" || label == "1.5x" || label == "2x",
                "走到 $cur 时文案为 $label，不在预期集合内",
            )
            assertTrue(cur in steps, "走到 $cur 不在档位表内")
        }
    }

    @Test
    fun twoStepsAdvanceTwoSlots() {
        // nextSpeed 可叠加：走两步恰好跳两档
        assertEquals(2f, VoicePlayer.nextSpeed(VoicePlayer.nextSpeed(1f)))
        assertEquals(1.5f, VoicePlayer.nextSpeed(VoicePlayer.nextSpeed(0.5f)))
        assertEquals(1f, VoicePlayer.nextSpeed(VoicePlayer.nextSpeed(2f)))
    }

    @Test
    fun nanIsTreatedAsUnknown() {
        // NaN 不等于任何档位，按未知值走「隐含第一档再 +1」
        assertEquals(1.5f, VoicePlayer.nextSpeed(Float.NaN), "NaN 应按未知值落到 1.5x")
        assertTrue(VoicePlayer.nextSpeed(Float.NaN) in steps)
    }

    @Test
    fun formatSpeedLabelTreatsOutOfRangeAsOneX() {
        // 非档位值统一兜底 "1x"
        assertEquals("1x", VoicePlayer.formatSpeedLabel(-0.5f))
        assertEquals("1x", VoicePlayer.formatSpeedLabel(3f))
        assertEquals("1x", VoicePlayer.formatSpeedLabel(100f))
        assertEquals("1x", VoicePlayer.formatSpeedLabel(Float.MAX_VALUE))
    }

    @Test
    fun labelOfNextFromUnknownMatchesTheLabelOfItsStep() {
        // 未知值 nextSpeed 落到 1.5f，对应文案必须一致，不能漂移
        assertEquals("1.5x", VoicePlayer.formatSpeedLabel(VoicePlayer.nextSpeed(7f)))
    }

    @Test
    fun twoFullRingsReturnToStart() {
        // 转两圈（8 步）也闭合，环不随圈数漂移
        steps.forEach { start ->
            var cur = start
            repeat(steps.size * 2) { cur = VoicePlayer.nextSpeed(cur) }
            assertEquals(start, cur, "从 $start 出发转两圈应回到起点，实际 $cur")
        }
    }
}
