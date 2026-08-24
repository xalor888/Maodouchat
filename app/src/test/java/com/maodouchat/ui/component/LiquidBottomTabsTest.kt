package com.maodouchat.ui.component

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidBottomTabsTest {

    @Test
    fun `snap rounds to nearest tab and coerces`() {
        assertEquals(0, snapNavigationIndex(0.4f, 4))
        assertEquals(1, snapNavigationIndex(0.5f, 4))
        assertEquals(3, snapNavigationIndex(3.4f, 4))
        assertEquals(0, snapNavigationIndex(-2f, 4))
        assertEquals(3, snapNavigationIndex(99f, 4))
    }

    @Test
    fun `glass container is translucent light or dark`() {
        val light = liquidGlassContainerColor(isLightTheme = true)
        val dark = liquidGlassContainerColor(isLightTheme = false)
        assertEquals(Color(0xFFFAFAFA).copy(alpha = 0.28f), light)
        assertEquals(Color(0xFF121212).copy(alpha = 0.32f), dark)
        assertTrue(light.alpha < 1f)
        assertTrue(dark.alpha < 1f)
    }

    @Test
    fun `selected pill follows Murexide 10 percent overlay`() {
        assertEquals(Color.Black.copy(alpha = 0.10f), liquidGlassSelectedPillColor(true))
        assertEquals(Color.White.copy(alpha = 0.10f), liquidGlassSelectedPillColor(false))
    }

    @Test
    fun `capsule tab index coerces into range`() {
        assertEquals(0, coerceCapsuleTabIndex(-1, 3))
        assertEquals(2, coerceCapsuleTabIndex(9, 3))
        assertEquals(0, coerceCapsuleTabIndex(0, 0))
        assertEquals(1, coerceCapsuleTabIndex(1, 3))
    }

    @Test
    fun `pinned dock matches capsule control sizes`() {
        assertEquals(64, PinnedBottomNavMetrics.BarHeight.value.toInt())
        assertEquals(22, PinnedBottomNavMetrics.IconSize.value.toInt())
        assertEquals(56, PinnedBottomNavMetrics.PillHeight.value.toInt())
        assertEquals(10f, PinnedBottomNavMetrics.LabelSize.value)
    }
}
