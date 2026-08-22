package com.maodouchat.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlindWatermarkLabelTest {
    // 固定时间戳，避免测试依赖当前时间；用同一格式化器计算期望后缀，时区无关。
    private val fixedTs = 1_700_000_000_000L
    private val timeSuffix = "·" + formatWatermarkTime(fixedTs)

    @Test
    fun `builds compact label with user and chat fragments`() {
        val label = buildBlindWatermarkLabel(
            userId = "user-abcdef012345",
            chatId = "chat-9876543210",
            deviceHint = "deadbeef",
            timestampMs = fixedTs
        )
        // shortenId keeps alphanumerics then takes first 8 / 4 chars; 时间戳用于取证归属。
        assertEquals("MC·userabcd·chat9876·dead$timeSuffix", label)
    }

    @Test
    fun `falls back when ids blank`() {
        assertEquals("MC·anon·chat$timeSuffix", buildBlindWatermarkLabel(null, null, null, fixedTs))
        assertEquals("MC·anon·chat$timeSuffix", buildBlindWatermarkLabel("", "", "", fixedTs))
    }

    @Test
    fun `omits device when blank`() {
        val label = buildBlindWatermarkLabel("alice", "room1", null, fixedTs)
        assertEquals("MC·alice·room1$timeSuffix", label)
        assertFalse(label.endsWith("·"))
        assertTrue(label.startsWith("MC·"))
    }

    @Test
    fun `label is never empty for secret attribution`() {
        val label = buildBlindWatermarkLabel("u1", "c1", null, fixedTs)
        assertTrue(label.isNotBlank())
        assertTrue(label.contains("u1"))
        assertTrue(label.contains("c1"))
    }

    @Test
    fun `visible overlay is skipped when disabled or label blank`() {
        assertFalse(shouldDrawBlindWatermark(enabled = false, label = "MC·u·c"))
        assertFalse(shouldDrawBlindWatermark(enabled = true, label = ""))
        assertFalse(shouldDrawBlindWatermark(enabled = true, label = "   "))
        assertTrue(shouldDrawBlindWatermark(enabled = true, label = "MC·u·c"))
    }

    @Test
    fun `theme-aware colors differ for dark and light`() {
        val dark = blindWatermarkTextColor(darkTheme = true)
        val light = blindWatermarkTextColor(darkTheme = false)
        assertEquals(BlindWatermarkColorDarkSurface, dark)
        assertEquals(BlindWatermarkColorLightSurface, light)
        assertTrue(dark != light)
    }

    @Test
    fun `alpha clamp is perceivable but not opaque`() {
        assertEquals(BLIND_WATERMARK_ALPHA_MIN, coerceBlindWatermarkAlpha(0.01f), 0f)
        assertEquals(BLIND_WATERMARK_ALPHA_MAX, coerceBlindWatermarkAlpha(1f), 0f)
        assertEquals(BLIND_WATERMARK_ALPHA_DEFAULT, coerceBlindWatermarkAlpha(BLIND_WATERMARK_ALPHA_DEFAULT), 0f)
        assertTrue(coerceBlindWatermarkAlpha(BLIND_WATERMARK_ALPHA_DEFAULT) > 0.22f)
    }
}
