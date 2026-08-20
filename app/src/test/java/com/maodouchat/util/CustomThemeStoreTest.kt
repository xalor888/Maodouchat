package com.maodouchat.util

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 9.253：自定义主题 .attheme 兼容解析契约测试（纯 JVM——项目未启用 Robolectric，
 * Context 相关的 set/get 存储路径由集成场景覆盖）。
 */
class CustomThemeStoreTest {

    @Test
    fun `parse attheme maps tg keys to slots`() {
        val text = """
            // community theme
            actionBarDefaultAction=-14509328
            chat_inBubble=#FFFFFFFF
            chat_outBubble=#EFFDDE
            unknownKey=#123456
            badline
        """.trimIndent()
        val parsed = CustomThemeStore.parseAtTheme(text)
        assertEquals(3, parsed.size)
        assertEquals(Color(0xFF229AF0), parsed["accent"]) // -14509328 十进制 int 值兼容解析
        assertEquals(Color(0xFFFFFFFF), parsed["chat_inBubble"])
        assertEquals(Color(0xFFEFFDDE), parsed["chat_outBubble"]) // 6 位 hex 自动补 FF alpha
        assertNull(parsed["unknownKey"])
    }

    @Test
    fun `parse attheme handles 8-digit hex and comments`() {
        val parsed = CustomThemeStore.parseAtTheme("windowBackgroundWhite=#FF0E1621\n// comment line\n")
        assertEquals(Color(0xFF0E1621), parsed["window_background"])
    }

    @Test
    fun `parse attheme empty or invalid yields empty`() {
        assertTrue(CustomThemeStore.parseAtTheme("").isEmpty())
        assertTrue(CustomThemeStore.parseAtTheme("no equals here\n===\n=abc").isEmpty())
        assertTrue(CustomThemeStore.parseAtTheme("chat_inBubble=notacolor").isEmpty())
    }

    @Test
    fun `parse attheme multiple mappings to same slot last wins`() {
        // chat_wallpaper 与 chat_messagePanelBackground 同映射 chat_background，后者覆盖
        val parsed = CustomThemeStore.parseAtTheme(
            "chat_wallpaper=#111111\nchat_messagePanelBackground=#222222"
        )
        assertEquals(Color(0xFF222222), parsed["chat_background"])
    }

    @Test
    fun `format argb is 8 digit hex`() {
        assertEquals("#FF3390EC", CustomThemeStore.formatArgb(Color(0xFF3390EC)))
        assertEquals("#80FFFFFF", CustomThemeStore.formatArgb(Color(0x80FFFFFF)))
    }

    @Test
    fun `export format round trips through parser`() {
        // 无 Context 场景：直接构造导出格式文本验证 round-trip 语法
        val line = "chat_inBubble=${CustomThemeStore.formatArgb(Color(0xFFFFFFFF))}"
        val reparsed = CustomThemeStore.parseAtTheme(line)
        assertEquals(Color(0xFFFFFFFF), reparsed["chat_inBubble"])
    }

    @Test
    fun `slot list is stable and covers tg map targets`() {
        // SLOTS 顺序即编辑器展示顺序，变更会改变 UI——锁定防意外重排
        assertEquals(
            listOf(
                "accent", "chat_background", "chat_inBubble", "chat_outBubble",
                "chat_outText", "text_primary", "window_background"
            ),
            CustomThemeStore.SLOTS
        )
    }
}
