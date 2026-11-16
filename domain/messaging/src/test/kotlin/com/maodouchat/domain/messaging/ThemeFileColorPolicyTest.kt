package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThemeFileColorPolicyTest {

    @Test
    fun parseColorValue_rgbHexGetsOpaqueAlpha() {
        assertEquals(0xFF112233.toInt(), ThemeFileColorPolicy.parseColorValue("#112233"))
    }

    @Test
    fun parseColorValue_argbHexAndDecimalInt() {
        assertEquals(0x80112233.toInt(), ThemeFileColorPolicy.parseColorValue("#80112233"))
        assertEquals(123456, ThemeFileColorPolicy.parseColorValue("123456"))
        assertNull(ThemeFileColorPolicy.parseColorValue("#12345"))
        assertNull(ThemeFileColorPolicy.parseColorValue("abc"))
    }

    @Test
    fun parseHexArgb_filtersNonHexChars() {
        assertEquals(0xFFFF1122.toInt(), ThemeFileColorPolicy.parseHexArgb("#FF1122"))
        assertEquals(0xFFAABBCC.toInt(), ThemeFileColorPolicy.parseHexArgb("  #aabbcc  "))
        assertNull(ThemeFileColorPolicy.parseHexArgb("#12345"))
        assertNull(ThemeFileColorPolicy.parseHexArgb("xyz"))
    }

    @Test
    fun parseAtTheme_mapsTgKeysAndIgnoresUnknown() {
        val out = ThemeFileColorPolicy.parseAtTheme(
            """chat_inBubble=#FF000000
windowBackgroundWhite=#FFFFFFFF
unknownKey=#112233
// comment

chat_outTextColor=123456
"""
        )
        assertEquals(0xFF000000.toInt(), out["chat_inBubble"])
        assertEquals(0xFFFFFFFF.toInt(), out["window_background"])
        assertEquals(123456, out["chat_outText"])
        assertEquals(3, out.size)
    }

    @Test
    fun parseThemeFile_nativeSlotsSupplementTgKeys() {
        val out = ThemeFileColorPolicy.parseThemeFile("""chat_inBubble=#FF000000
accent=#112233
""")
        assertEquals(0xFF000000.toInt(), out["chat_inBubble"])
        assertEquals(0xFF112233.toInt(), out["accent"])
    }

    @Test
    fun parseThemeFile_tgKeyWinsOverNativeSlot() {
        val out = ThemeFileColorPolicy.parseThemeFile("""chat_inBubble=#FF000000
chat_inBubble=#FFFFFFFF
""")
        assertEquals(0xFFFFFFFF.toInt(), out["chat_inBubble"])
    }

    @Test
    fun parseThemeFile_nativeDecimalFallback() {
        // 原 app 侧 parseThemeFile 是 hex 优先、decimal 兜底："123456" 是合法 6 位 hex，
        // 先被 parseHexArgb 吃掉；只有 hex 长度不合（如 9 位）才落到 toInt 兜底。
        val out = ThemeFileColorPolicy.parseThemeFile("""accent=123456
text_primary=123456789
""")
        assertEquals(0xFF123456.toInt(), out["accent"])
        assertEquals(123456789, out["text_primary"])
    }

    @Test
    fun formatArgb_roundTrips() {
        assertEquals("#FF112233", ThemeFileColorPolicy.formatArgb(0xFF112233.toInt()))
        assertEquals("#00000000", ThemeFileColorPolicy.formatArgb(0))
    }
}
