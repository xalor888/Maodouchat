package com.maodouchat.ui.screen.settings

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.maodouchat.ui.theme.ChatPalette
import com.maodouchat.ui.theme.SentBubbleSpec
import com.maodouchat.ui.theme.ThemePaint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G160：`ThemeEditorScreen.slotDefaultColor` 的测试（G160 刚从 defaultSlotColor 抽出）。
 *
 * 手搭一个「每个字段一个唯一色」的 ThemePaint，就能逐槽位断言
 * **精确取到哪个字段**——这比「颜色非空」或「与某个内置主题相等」都强。
 */
class ThemeSlotColorTest {

    // 每个槽位一个唯一色，便于反查
    private val cPrimary = Color(0xFF111111.toInt())
    private val cBackground = Color(0xFF222222.toInt())
    private val cChatBg = Color(0xFF333333.toInt())
    private val cInBubble = Color(0xFF444444.toInt())
    private val cTextPrimary = Color(0xFF555555.toInt())
    private val cInputBg = Color(0xFF666666.toInt())
    private val cUnread = Color(0xFF777777.toInt())
    private val cSystemMsg = Color(0xFF888888.toInt())
    private val cSentColor = Color(0xFF999999.toInt())
    private val cSentContent = Color(0xFFAAAAAA.toInt())
    private val WHITE = Color.White

    private val palette = ChatPalette(
        chatBackground = cChatBg,
        chatBubbleReceived = cInBubble,
        chatBubbleReceivedBorder = cChatBg,
        chatInputBackground = cInputBg,
        chatInputBorder = cChatBg,
        chatInputPlaceholder = cChatBg,
        systemMessageBackground = cSystemMsg,
        systemMessageText = cChatBg,
        textHint = cChatBg,
        textPrimary = cTextPrimary,
        textSecondary = cChatBg,
        divider = cChatBg,
        unreadRed = cUnread,
        onlineGreen = cChatBg,
        chatElevatedSurface = cChatBg,
        chatElevatedSurfaceHigh = cChatBg,
    )

    private val scheme = lightColorScheme(primary = cPrimary, background = cBackground)

    private fun paint(spec: SentBubbleSpec?) = ThemePaint(
        colorScheme = scheme,
        chatPalette = palette,
        sentBubbleSpec = spec,
    )

    private val withSpec = paint(SentBubbleSpec(cSentColor, cSentContent, cSentContent))
    private val withoutSpec = paint(null)

    @Test
    fun `every slot maps to its own field`() {
        val expected = mapOf(
            "accent" to cPrimary,
            "chat_background" to cChatBg,
            "chat_inBubble" to cInBubble,
            "chat_inText" to cTextPrimary,
            "chat_outBubble" to cSentColor,
            "chat_outText" to cSentContent,
            "text_primary" to cTextPrimary,
            "input_background" to cInputBg,
            "unread_badge" to cUnread,
            "system_message" to cSystemMsg,
        )
        expected.forEach { (slot, color) ->
            assertEquals("槽位 $slot 取错了字段", color, slotDefaultColor(withSpec, slot))
        }
    }

    @Test
    fun `out bubble and out text fall back when the spec is missing`() {
        assertEquals("chat_outBubble 应回落到 primary", cPrimary, slotDefaultColor(withoutSpec, "chat_outBubble"))
        assertEquals("chat_outText 应回落到白色", WHITE, slotDefaultColor(withoutSpec, "chat_outText"))
    }

    @Test
    fun `unknown slot falls back to the scheme background`() {
        assertEquals(cBackground, slotDefaultColor(withSpec, "no_such_slot"))
        assertEquals(cBackground, slotDefaultColor(withSpec, ""))
        // 大小写敏感：ACcent 不是 accent
        assertEquals(cBackground, slotDefaultColor(withSpec, "Accent"))
    }

    @Test
    fun `in text and text primary share the same field`() {
        assertEquals(
            slotDefaultColor(withSpec, "chat_inText"),
            slotDefaultColor(withSpec, "text_primary"),
        )
    }

    @Test
    fun `every slot resolves to one of the nine distinct colors`() {
        val slots = listOf(
            "accent", "chat_background", "chat_inBubble", "chat_inText", "chat_outBubble",
            "chat_outText", "text_primary", "input_background", "unread_badge", "system_message",
            "unknown",
        )
        slots.forEach { slot ->
            val c = slotDefaultColor(withSpec, slot)
            assertTrue("槽位 $slot 解析出意外颜色", c in setOf(
                cPrimary, cBackground, cChatBg, cInBubble, cTextPrimary,
                cInputBg, cUnread, cSystemMsg, cSentColor, cSentContent, WHITE,
            ))
        }
    }

    @Test
    fun `a null spec never throws`() {
        // 两条兜底路径都走一遍，确保不 NPE
        listOf(
            "accent", "chat_background", "chat_inBubble", "chat_inText", "chat_outBubble",
            "chat_outText", "text_primary", "input_background", "unread_badge",
            "system_message", "whatever",
        ).forEach { slotDefaultColor(withoutSpec, it) }
    }
}
