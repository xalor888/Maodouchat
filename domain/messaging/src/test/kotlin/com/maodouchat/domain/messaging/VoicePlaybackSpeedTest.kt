package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `VoicePlaybackSpeed` 倍速档位环：`nextSpeed` / `formatSpeedLabel` 纯函数钉住。
 */
class VoicePlaybackSpeedTest {

    private val steps = VoicePlaybackSpeed.SPEED_STEPS.toList()

    @Test
    fun speedStepsAreTheExpectedRing() {
        // 把顺序钉住：改顺序就是改用户体验，而且会让下面几条一起失效
        assertEquals(listOf(1f, 1.5f, 2f, 0.5f), steps, "SPEED_STEPS 的顺序变了")
    }

    @Test
    fun nextSpeedWalksTheRingInOrder() {
        assertEquals(1.5f, VoicePlaybackSpeed.nextSpeed(1f))
        assertEquals(2f, VoicePlaybackSpeed.nextSpeed(1.5f))
        assertEquals(0.5f, VoicePlaybackSpeed.nextSpeed(2f), "2x 之后应绕到最慢档（顺序设计如此）")
        assertEquals(1f, VoicePlaybackSpeed.nextSpeed(0.5f), "0.5x 之后应回到 1x，闭合整圈")
    }

    @Test
    fun nextSpeedReturnsToStartAfterAFullRing() {
        // 环的闭合性：转 len 次必须回到原点
        steps.forEach { start ->
            var cur = start
            repeat(steps.size) { cur = VoicePlaybackSpeed.nextSpeed(cur) }
            assertEquals(start, cur, "从 $start 出发转 ${steps.size} 次应回到起点，实际 $cur")
        }
    }

    @Test
    fun unknownSpeedAdvancesFromTheImplicitFirstStep() {
        // 未知值按 idx=0 处理再 +1，所以落到 steps[1]=1.5f 而不是 1f
        listOf(0.75f, 3f, 0f, -1f, 1.25f, 100f).forEach { unknown ->
            assertEquals(1.5f, VoicePlaybackSpeed.nextSpeed(unknown), "未知倍速 $unknown 应从隐含第一档往前一格")
        }
    }

    @Test
    fun unknownSpeedIsNeverReturnedAsIs() {
        // 兜底不能让垃圾值继续流传：返回值必须仍是档位表成员
        listOf(0.75f, 3f, 0f, -1f, 1.25f, 100f).forEach { unknown ->
            val out = VoicePlaybackSpeed.nextSpeed(unknown)
            assertTrue(out in steps, "nextSpeed($unknown)=$out 必须落在档位表内")
        }
    }

    @Test
    fun nextSpeedAlwaysReturnsAKnownStep() {
        // 任意输入（包括垃圾值）的返回值必须仍是档位表里的一员
        listOf(-1f, 0f, 0.25f, 0.5f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 99f, Float.MAX_VALUE)
            .forEach { input ->
                val out = VoicePlaybackSpeed.nextSpeed(input)
                assertTrue(out in steps, "nextSpeed($input)=$out 不在档位表 $steps 内")
            }
    }

    @Test
    fun formatSpeedLabelMatchesEveryStep() {
        assertEquals("0.5x", VoicePlaybackSpeed.formatSpeedLabel(0.5f))
        assertEquals("1x", VoicePlaybackSpeed.formatSpeedLabel(1f))
        assertEquals("1.5x", VoicePlaybackSpeed.formatSpeedLabel(1.5f))
        assertEquals("2x", VoicePlaybackSpeed.formatSpeedLabel(2f))
        // 未知值 → "1x"（与 nextSpeed 的回落一致）
        assertEquals("1x", VoicePlaybackSpeed.formatSpeedLabel(7f))
    }

    @Test
    fun everyStepHasALabelAndEveryLabelRoundTrips() {
        // 档位 ↔ 文案必须一一对应：不能让某个档位显示成另一档的文案
        steps.forEach { step ->
            val label = VoicePlaybackSpeed.formatSpeedLabel(step)
            assertTrue(label.endsWith("x"), "$step 的文案应形如 'Nx'，实际 $label")
            assertTrue(label != "1x" || step == 1f, "$step 不应显示成 1x")
        }
        // 四个档位对应四个不同文案
        assertEquals(steps.size, steps.map { VoicePlaybackSpeed.formatSpeedLabel(it) }.distinct().size,
            "四个档位应有四个不同文案")
    }

    @Test
    fun nextSpeedAndLabelsAgreeForEveryReachableState() {
        // 从任一起点出发走几步，文案始终与状态一致
        var cur = 1f
        repeat(steps.size * 2) {
            cur = VoicePlaybackSpeed.nextSpeed(cur)
            val label = VoicePlaybackSpeed.formatSpeedLabel(cur)
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
        assertEquals(2f, VoicePlaybackSpeed.nextSpeed(VoicePlaybackSpeed.nextSpeed(1f)))
        assertEquals(1.5f, VoicePlaybackSpeed.nextSpeed(VoicePlaybackSpeed.nextSpeed(0.5f)))
        assertEquals(1f, VoicePlaybackSpeed.nextSpeed(VoicePlaybackSpeed.nextSpeed(2f)))
    }

    @Test
    fun nanIsTreatedAsUnknown() {
        // NaN 不等于任何档位，按未知值走「隐含第一档再 +1」
        assertEquals(1.5f, VoicePlaybackSpeed.nextSpeed(Float.NaN), "NaN 应按未知值落到 1.5x")
        assertTrue(VoicePlaybackSpeed.nextSpeed(Float.NaN) in steps)
    }

    @Test
    fun formatSpeedLabelTreatsOutOfRangeAsOneX() {
        // 非档位值统一兜底 "1x"
        assertEquals("1x", VoicePlaybackSpeed.formatSpeedLabel(-0.5f))
        assertEquals("1x", VoicePlaybackSpeed.formatSpeedLabel(3f))
        assertEquals("1x", VoicePlaybackSpeed.formatSpeedLabel(100f))
        assertEquals("1x", VoicePlaybackSpeed.formatSpeedLabel(Float.MAX_VALUE))
    }

    @Test
    fun labelOfNextFromUnknownMatchesTheLabelOfItsStep() {
        // 未知值 nextSpeed 落到 1.5f，对应文案必须一致，不能漂移
        assertEquals("1.5x", VoicePlaybackSpeed.formatSpeedLabel(VoicePlaybackSpeed.nextSpeed(7f)))
    }

    @Test
    fun twoFullRingsReturnToStart() {
        // 转两圈（8 步）也闭合，环不随圈数漂移
        steps.forEach { start ->
            var cur = start
            repeat(steps.size * 2) { cur = VoicePlaybackSpeed.nextSpeed(cur) }
            assertEquals(start, cur, "从 $start 出发转两圈应回到起点，实际 $cur")
        }
    }

    @Test
    fun nextSpeedFromInfinityAndNegativeZero() {
        listOf(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0f).forEach {
            assertEquals(1.5f, VoicePlaybackSpeed.nextSpeed(it), "$it 应按未知值落到 1.5x")
        }
    }

    @Test
    fun formatSpeedLabelForNaNAndNegativeZero() {
        assertEquals("1x", VoicePlaybackSpeed.formatSpeedLabel(Float.NaN))
        assertEquals("1x", VoicePlaybackSpeed.formatSpeedLabel(-0f))
    }

    @Test
    fun threeStepsAdvanceThreeSlots() {
        assertEquals(0.5f, VoicePlaybackSpeed.nextSpeed(VoicePlaybackSpeed.nextSpeed(VoicePlaybackSpeed.nextSpeed(1f))))
        assertEquals(2f, VoicePlaybackSpeed.nextSpeed(VoicePlaybackSpeed.nextSpeed(VoicePlaybackSpeed.nextSpeed(0.5f))))
        assertEquals(1.5f, VoicePlaybackSpeed.nextSpeed(VoicePlaybackSpeed.nextSpeed(VoicePlaybackSpeed.nextSpeed(2f))))
    }

    @Test
    fun speedStepsAreDistinctAndBounded() {
        assertEquals(4, steps.size)
        assertEquals(4, steps.toSet().size)
        steps.forEach { assertTrue(it > 0f && it <= 2f, "$it 应在 (0, 2] 内") }
    }

    @Test
    fun labelOfNextMatchesExpectedLabelForEachStep() {
        mapOf(1f to "1.5x", 1.5f to "2x", 2f to "0.5x", 0.5f to "1x").forEach { (step, label) ->
            assertEquals(label, VoicePlaybackSpeed.formatSpeedLabel(VoicePlaybackSpeed.nextSpeed(step)),
                "$step 的下一档文案应为 $label")
        }
    }

    @Test
    fun neighboringFloatsAreUnknownForBothFunctions() {
        // 档位是精确 Float 相等：上下一个可表示浮点都已不是档位
        steps.forEach { step ->
            val up = Math.nextAfter(step, Double.POSITIVE_INFINITY).toFloat()
            val down = Math.nextAfter(step, Double.NEGATIVE_INFINITY).toFloat()
            assertTrue(up != step && down != step, "$step 的邻值应与档位不同")
            assertEquals(1.5f, VoicePlaybackSpeed.nextSpeed(up), "$up 应按未知值落到 1.5x")
            assertEquals(1.5f, VoicePlaybackSpeed.nextSpeed(down), "$down 应按未知值落到 1.5x")
            assertEquals("1x", VoicePlaybackSpeed.formatSpeedLabel(up))
            assertEquals("1x", VoicePlaybackSpeed.formatSpeedLabel(down))
        }
    }

    @Test
    fun labelsParseBackToTheirSteps() {
        // 文案去掉末尾 x 必须恰好解析回档位值，不能是近似文案
        steps.forEach { step ->
            assertEquals(step, VoicePlaybackSpeed.formatSpeedLabel(step).dropLast(1).toFloat())
        }
    }

    @Test
    fun unknownValueJoinsRingAndClosesAfterFourSteps() {
        // 未知值先并入环（落到 1.5x），再走 4 步恰好回到并入后 4 步的位置
        listOf(9f, Float.NaN).forEach { unknown ->
            var cur = VoicePlaybackSpeed.nextSpeed(unknown)
            assertEquals(1.5f, cur, "$unknown 应先并入 1.5x")
            repeat(4) { cur = VoicePlaybackSpeed.nextSpeed(cur) }
            assertEquals(1.5f, cur, "从 $unknown 并入后转 4 步应回到 1.5x")
        }
    }

    @Test
    fun extremeMagnitudesAreUnknown() {
        listOf(Float.MIN_VALUE, -Float.MAX_VALUE, 1e30f).forEach { extreme ->
            assertEquals(1.5f, VoicePlaybackSpeed.nextSpeed(extreme), "$extreme 应按未知值落到 1.5x")
            assertEquals("1x", VoicePlaybackSpeed.formatSpeedLabel(extreme))
        }
    }

    @Test
    fun labelOfNextFromAnyUnknownIsOnePointFiveX() {
        listOf(0f, 3f, Float.NaN, Float.MAX_VALUE, -1f).forEach { unknown ->
            assertEquals("1.5x", VoicePlaybackSpeed.formatSpeedLabel(VoicePlaybackSpeed.nextSpeed(unknown)))
        }
    }

    @Test
    fun playerSpeed_cycles() {
        assertEquals(1.5f, VoicePlaybackSpeed.nextSpeed(1f), 0.0001f)
        assertEquals(2f, VoicePlaybackSpeed.nextSpeed(1.5f), 0.0001f)
        assertEquals(0.5f, VoicePlaybackSpeed.nextSpeed(2f), 0.0001f)
        assertEquals(1f, VoicePlaybackSpeed.nextSpeed(0.5f), 0.0001f)
        assertEquals(1.5f, VoicePlaybackSpeed.nextSpeed(0.75f), 0.0001f) // unknown → treat as index 0
        assertEquals("1.5x", VoicePlaybackSpeed.formatSpeedLabel(1.5f))
        assertEquals("2x", VoicePlaybackSpeed.formatSpeedLabel(2f))
        assertEquals("0.5x", VoicePlaybackSpeed.formatSpeedLabel(0.5f))
        assertEquals("1x", VoicePlaybackSpeed.formatSpeedLabel(1f))
    }

    @Test
    fun formatSpeedLabel_unknownSpeedsFallBackToOneX() {
        assertEquals("1x", VoicePlaybackSpeed.formatSpeedLabel(0.75f))
        assertEquals("1x", VoicePlaybackSpeed.formatSpeedLabel(3f))
        assertEquals("1x", VoicePlaybackSpeed.formatSpeedLabel(0f))
    }
}
