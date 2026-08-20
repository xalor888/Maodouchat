package com.maodouchat.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 9.255：WCAG 对比度保证机制契约测试（借鉴 Murexide）。
 * 自定义气泡色的文字深浅选择必须保证 4.5:1 可读性。
 */
class ContrastGuardTest {

    @Test
    fun `white bubble gets dark text`() {
        val colors = resolveSentBubble(null, true, Color(0xFFFFFFFF))
        assertEquals(Color(0xFF212121), colors.content)
    }

    @Test
    fun `black bubble gets white text`() {
        val colors = resolveSentBubble(null, true, Color(0xFF000000))
        assertEquals(TextWhite, colors.content)
    }

    @Test
    fun `mid gray bubble still picks compliant side`() {
        // 中灰（旧 0.6 亮度阈值失效区）：必须选对比度更高的一侧——纯 #808080
        // 数学上两侧都无法达 4.5（白字 4.08/黑字 4.09），兜底取更优侧；
        // 常见品牌色均能在两侧之一达标
        val gray = Color(0xFF808080)
        val colors = resolveSentBubble(null, true, gray)
        val ratio = contrastRatio(colors.content, gray)
        val darkRatio = contrastRatio(Color(0xFF212121), gray)
        val whiteRatio = contrastRatio(TextWhite, gray)
        assertTrue(ratio >= maxOf(darkRatio, whiteRatio) - 0.01f, "must pick the better side")
    }

    @Test
    fun `brand blue bubble gets white text`() {
        val colors = resolveSentBubble(null, true, Color(0xFF007AFF))
        assertEquals(TextWhite, colors.content)
    }

    @Test
    fun `theme spec wins when user not customized`() {
        val spec = SentBubbleSpec(Color(0xFFEFFDDE), Color(0xFF212121), Color(0xFF52914A))
        val colors = resolveSentBubble(spec, false, Color(0xFF000000))
        assertEquals(Color(0xFFEFFDDE), colors.bubble)
        assertEquals(Color(0xFF212121), colors.content)
    }

    @Test
    fun `user customized overrides theme spec`() {
        val spec = SentBubbleSpec(Color(0xFFEFFDDE), Color(0xFF212121), Color(0xFF52914A))
        val colors = resolveSentBubble(spec, true, Color(0xFFFF2D55))
        assertEquals(Color(0xFFFF2D55), colors.bubble)
    }

    @Test
    fun `contrast ratio extremes`() {
        assertEquals(21f, contrastRatio(Color.White, Color.Black), 0.05f)
        assertEquals(1f, contrastRatio(Color.White, Color.White), 0.01f)
    }
}
