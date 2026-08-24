package com.maodouchat.theme

import androidx.compose.ui.graphics.Color
import com.maodouchat.ui.theme.ThemeFamily
import com.maodouchat.ui.theme.resolveSentBubble
import com.maodouchat.ui.theme.resolveThemePaint
import com.maodouchat.util.ThemePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull

import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeStyleTest {

    @Test
    fun `normalize maps known ids and falls back to maodou`() {
        assertEquals(ThemeFamily.MAODOU, ThemeFamily.normalize("maodou"))
        assertEquals(ThemeFamily.MAODOU, ThemeFamily.normalize("TG_Classic"))
        assertEquals(ThemeFamily.MAODOU, ThemeFamily.normalize(" tg_midnight "))
        assertEquals(ThemeFamily.MAODOU, ThemeFamily.normalize("tg_graphite"))
        assertEquals(ThemeFamily.MAODOU, ThemeFamily.normalize("telegram"))
        assertEquals(ThemeFamily.MAODOU, ThemeFamily.normalize(null))
        assertEquals(ThemeFamily.MAODOU, ThemeFamily.normalize("unknown"))
        assertEquals("maodou", ThemePreferences.normalizeStyle("tg_midnight"))
        assertEquals("maodou", ThemePreferences.normalizeStyle("default"))
        assertEquals(listOf(ThemeFamily.MAODOU), ThemeFamily.PICKABLE)
    }

    @Test
    fun `default paint uses white surfaces and gray ink bubbles`() {
        val lightPaint = resolveThemePaint(ThemeFamily.MAODOU, dark = false)
        val darkPaint = resolveThemePaint(ThemeFamily.MAODOU, dark = true)
        val light = lightPaint.sentBubbleSpec
        val dark = darkPaint.sentBubbleSpec
        assertNotNull(light)
        assertNotNull(dark)
        assertEquals(Color(0xFFFFFFFF), lightPaint.colorScheme.background)
        assertEquals(Color(0xFFFFFFFF), lightPaint.colorScheme.surface)
        assertEquals(Color(0xFFF2F2F2), light!!.color)
        assertEquals(Color(0xFF1A1A1A), light.content)
        assertEquals(Color(0xFF111111), darkPaint.colorScheme.background)
        assertEquals(Color(0xFF2A2A2A), dark!!.color)
        assertEquals(Color(0xFFF2F2F2), lightPaint.chatPalette.chatBubbleReceived)
    }

    @Test
    fun `legacy tg ids still paint as maodou white theme`() {
        listOf(false, true).forEach { dark ->
            val paint = resolveThemePaint(ThemeFamily.normalize("tg_classic"), dark)
            assertEquals(ThemeFamily.MAODOU, ThemeFamily.normalize("tg_classic"))
            assertNotNull("maodou dark=$dark should have sent spec", paint.sentBubbleSpec)
            if (!dark) {
                assertEquals(Color(0xFFFFFFFF), paint.colorScheme.background)
                assertEquals(Color(0xFFF2F2F2), paint.sentBubbleSpec!!.color)
            }
        }
    }

    @Test
    fun `default light sent bubble uses gray with dark ink not purple`() {
        val spec = resolveThemePaint(ThemeFamily.MAODOU, dark = false).sentBubbleSpec!!
        assertEquals(Color(0xFFF2F2F2), spec.color)
        assertEquals(Color(0xFF1A1A1A), spec.content)
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

    @Test
    fun `night window crossing midnight covers both sides`() {
        // 21:00 → 07:00 跨午夜窗口
        assertTrue(ThemePreferences.isWithinNightWindow(22 * 60, 21 * 60, 7 * 60))
        assertTrue(ThemePreferences.isWithinNightWindow(2 * 60, 21 * 60, 7 * 60))
        assertTrue(ThemePreferences.isWithinNightWindow(21 * 60, 21 * 60, 7 * 60))
        assertFalse(ThemePreferences.isWithinNightWindow(7 * 60, 21 * 60, 7 * 60))
        assertFalse(ThemePreferences.isWithinNightWindow(12 * 60, 21 * 60, 7 * 60))
    }

    @Test
    fun `night window within same day`() {
        // 09:00 → 17:00 同天窗口
        assertTrue(ThemePreferences.isWithinNightWindow(10 * 60, 9 * 60, 17 * 60))
        assertFalse(ThemePreferences.isWithinNightWindow(17 * 60, 9 * 60, 17 * 60))
        assertFalse(ThemePreferences.isWithinNightWindow(8 * 60, 9 * 60, 17 * 60))
    }

    @Test
    fun `theme mode normalize supports scheduled`() {
        assertEquals("scheduled", ThemePreferences.normalize("scheduled"))
        assertEquals("scheduled", ThemePreferences.normalize(" Scheduled "))
        assertEquals("system", ThemePreferences.normalize("unknown"))
    }
}
