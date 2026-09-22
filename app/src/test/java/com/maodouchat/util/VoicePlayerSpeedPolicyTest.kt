package com.maodouchat.util

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * G208b：`VoicePlayer` 倍速策略。
 *
 * `nextSpeed` 的 KDoc 自己写着「纯函数，供 UI/单测」，却**一条测试都没有**。
 * 这里的判据不是「记住几个例子」，而是三条**会在将来被破坏的性质**：
 *
 * 1. **档位环完整**：`SPEED_STEPS = [1, 1.5, 2, 0.5]`——注意顺序是
 *    「快三档，然后突然回到最慢」。这不是笔误：往上加到底后用 0.5x 收尾。
 *    所以 `1 → 1.5 → 2 → 0.5 → 1` 转一圈必须回到起点；
 * 2. **未知值落到第一档**（而不是就近取整、也不是最后一档）；
 * 3. **`formatSpeedLabel` 与档位一致**——任何档位（含未知值回落）
 *    都必须有对应文案，不能出现「状态里是 3x、界面上显示 1x」。
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
        // ⚠️ 我第一版断言「未知值回落到第一档（1f）」，**错了**：实际返回 1.5f。
        // 读代码才看清：`indexOfFirst` 找不到时把 idx 设成 **0**，然后**照样 +1**，
        // 所以取到 steps[1]=1.5f。语义是「就当你在第一档，然后按下一档」。
        // 这不是 bug（从垃圾状态按一次「下一档」得到 1.5x 完全合理），
        // 但顺带露出一个**真实的不对称**：`formatSpeedLabel(未知)` 给 "1x"，
        // 而 `nextSpeed(未知)` 给 1.5x——两者各自的兜底不同，值不一样。
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
}
