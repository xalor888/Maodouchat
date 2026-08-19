package com.maodouchat.theme

import androidx.compose.ui.graphics.Color
import com.maodouchat.ui.theme.ThemeFamily
import com.maodouchat.ui.theme.resolveSentBubble
import com.maodouchat.ui.theme.resolveThemePaint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeStyleTest {

    @Test
    fun `normalize maps known ids and falls back to maodou`() {
        assertEquals(ThemeFamily.MAODOU, ThemeFamily.normalize("maodou"))
        assertEquals(ThemeFamily.TG_CLASSIC, ThemeFamily.normalize("TG_Classic"))
        assertEquals(ThemeFamily.TG_MIDNIGHT, ThemeFamily.normalize(" tg_midnight "))
        assertEquals(ThemeFamily.TG_GRAPHITE, ThemeFamily.normalize("tg_graphite"))
        assertEquals(ThemeFamily.MAODOU, ThemeFamily.normalize(null))
        assertEquals(ThemeFamily.MAODOU, ThemeFamily.normalize("unknown"))
    }

    @Test
    fun `maodou paint has no sent bubble override`() {
        assertNull(resolveThemePaint(ThemeFamily.MAODOU, dark = false).sentBubbleSpec)
        assertNull(resolveThemePaint(ThemeFamily.MAODOU, dark = true).sentBubbleSpec)
    }

    @Test
    fun `tg families always provide sent bubble spec with light and dark variants`() {
        listOf(ThemeFamily.TG_CLASSIC, ThemeFamily.TG_MIDNIGHT, ThemeFamily.TG_GRAPHITE).forEach { family ->
            listOf(false, true).forEach { dark ->
                val paint = resolveThemePaint(family, dark)
                assertNotNull("$family dark=$dark should have sent spec", paint.sentBubbleSpec)
            }
        }
    }

    @Test
    fun `tg classic light uses the signature green bubble with dark content`() {
        val spec = resolveThemePaint(ThemeFamily.TG_CLASSIC, dark = false).sentBubbleSpec!!
        assertEquals(Color(0xFFEFFDDE), spec.color)
        // 绿气泡必须配深色文字，否则不可读
        assertEquals(Color(0xFF212121), spec.content)
    }

    @Test
    fun `theme spec wins when user never customized bubble color`() {
        val spec = resolveThemePaint(ThemeFamily.TG_CLASSIC, dark = false).sentBubbleSpec!!
        val resolved = resolveSentBubble(spec, userCustomized = false, userColor = Color(0xFF007AFF))
        assertEquals(spec.color, resolved.bubble)
        assertEquals(spec.content, resolved.content)
    }

    @Test
    fun `user customization takes priority over theme spec`() {
        val spec = resolveThemePaint(ThemeFamily.TG_CLASSIC, dark = false).sentBubbleSpec!!
        val userColor = Color(0xFF8B5CF6)
        val resolved = resolveSentBubble(spec, userCustomized = true, userColor = userColor)
        assertEquals(userColor, resolved.bubble)
    }

    @Test
    fun `high luminance user bubble gets dark text`() {
        val bright = Color(0xFFF5F5DC)
        val resolved = resolveSentBubble(null, userCustomized = true, userColor = bright)
        assertEquals(bright, resolved.bubble)
        assertEquals(Color(0xFF212121), resolved.content)
    }

    @Test
    fun `dark user bubble keeps white text`() {
        val dark = Color(0xFF007AFF)
        val resolved = resolveSentBubble(null, userCustomized = true, userColor = dark)
        assertEquals(Color(0xFFFFFFFF), resolved.content)
    }
}
