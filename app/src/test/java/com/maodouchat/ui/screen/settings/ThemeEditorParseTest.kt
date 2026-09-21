package com.maodouchat.ui.screen.settings

import androidx.compose.ui.graphics.Color
import com.maodouchat.util.CustomThemeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G151：主题编辑器里两个解析函数的测试（`ThemeEditorScreen.kt` 的 private 函数，
 * 经同包测试直接访问）。
 *
 * `parseHexColor` 的怪癖：它会**静默过滤掉所有非十六进制字符**，
 * 所以 `"#12x34x56"` 与 `"#123456"` 等价——这是实现细节，但必须钉住，
 * 否则「以为用户传了非法值会被拒」的改动会静默改变行为。
 *
 * `parseThemeFile` 的怪癖：at-theme 行（经 `TG_KEY_MAP` 映射）先落地，
 * 随后的 `key = value` 行**不覆盖已有 key**（`out.containsKey(key)` 直接跳过）。
 */
class ThemeEditorParseTest {

    // ─── parseHexColor ───

    @Test
    fun `six digit hex gets an opaque alpha`() {
        // #RRGGBB -> 自动补 FF alpha
        assertEquals(Color(0xFFFF1234.toInt()), parseHexColor("#FF1234"))
        assertEquals(Color(0xFFFF1234.toInt()), parseHexColor("FF1234"))
        assertEquals(Color(0xFF000000.toInt()), parseHexColor("#000000"))
    }

    @Test
    fun `eight digit hex is argb with its own alpha`() {
        assertEquals(Color(0x80123456.toInt()), parseHexColor("#80123456"))
        assertEquals(Color(0x00123456.toInt()), parseHexColor("00123456"))
        // 8 位不再补 alpha
        assertEquals(Color(0xFFFF1234.toInt()), parseHexColor("FFFF1234"))
    }

    @Test
    fun `illegal characters are silently filtered out`() {
        // 过滤后等价于 #123456
        assertEquals(parseHexColor("#123456"), parseHexColor("#12x34x56"))
        assertEquals(parseHexColor("#123456"), parseHexColor("# 12 34 56 !!"))
        assertEquals(parseHexColor("#abcdef"), parseHexColor("#abc-def"))
    }

    @Test
    fun `wrong length returns null`() {
        assertNull(parseHexColor(""))
        assertNull(parseHexColor("#"))
        assertNull(parseHexColor("#123"))     // 3 位
        assertNull(parseHexColor("#12345"))   // 5 位
        assertNull(parseHexColor("#1234567")) // 7 位
        assertNull(parseHexColor("#123456789")) // 9 位
    }

    @Test
    fun `all illegal characters return null`() {
        assertNull(parseHexColor("#xyz"))
        assertNull(parseHexColor("gggggg"))
    }

    // ─── parseThemeFile ───

    @Test
    fun `at theme colors come through`() {
        // actionBarDefaultAction 是 TG 键，映射到 accent 槽位
        val out = parseThemeFile("actionBarDefaultAction=#FF1234")
        assertEquals(Color(0xFFFF1234.toInt()), out["accent"])
    }

    @Test
    fun `slot key value line adds a color`() {
        // #FF00FF00 是 8 位 ARGB：A=FF, R=00, G=FF, B=00
        val out = parseThemeFile("chat_inBubble=#FF00FF00")
        assertEquals(Color(0xFF00FF00.toInt()), out["chat_inBubble"])
    }

    @Test
    fun `value line cannot override an at theme color`() {
        // at-theme 先落地，随后同 key 的 value 行被 containsKey 跳过
        val out = parseThemeFile(
            "actionBarDefaultAction=#FF1234\naccent=#FF00FF00"
        )
        assertEquals(Color(0xFFFF1234.toInt()), out["accent"])
    }

    @Test
    fun `unknown keys and comments and malformed lines are ignored`() {
        val out = parseThemeFile(
            """
            // 这是注释
            # 也是注释（不以 // 开头但 # 不是合法 key 前缀，仍会被 unknown key 挡掉）
            not_a_slot=#FF123456
            =#FF123456
            noequalsign
            chat_inBubble=#FF123456
            """.trimIndent()
        )
        assertEquals(1, out.size)
        // #FF123456 是 8 位 ARGB
        assertEquals(Color(0xFF123456.toInt()), out["chat_inBubble"])
    }

    @Test
    fun `unparsable value line is skipped`() {
        val out = parseThemeFile("chat_inBubble=not-a-color")
        assertTrue(out.isEmpty())
    }

    @Test
    fun `integer color values are accepted by the value line`() {
        // parseThemeFile 对 value 行还有一条退路：Color(value.toInt())
        val out = parseThemeFile("chat_inBubble=-65536")
        assertEquals(Color(-65536), out["chat_inBubble"])
    }

    @Test
    fun `every slot key is accepted by the value line`() {
        // SLOTS 里的每个键都应该能通过 value 行落地（未知键才被忽略）
        CustomThemeStore.SLOTS.forEach { slot ->
            val out = parseThemeFile("$slot=#FF123456")
            assertEquals("槽位 $slot 没被接受", 1, out.size)
        }
    }
}
