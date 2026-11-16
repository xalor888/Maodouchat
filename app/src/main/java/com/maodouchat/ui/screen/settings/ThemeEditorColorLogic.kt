package com.maodouchat.ui.screen.settings

import androidx.compose.ui.graphics.Color
import com.maodouchat.domain.messaging.ThemeFileColorPolicy

/**
 * 槽位 → 主题绘制参数的映射（G160 从 `defaultSlotColor` 抽出）。
 *
 * 原先这段映射和「读全局 ThemePreferences 解析 paint」耦在一个函数里，
 * 导致它无法被单测覆盖。拆开后映射本身是纯函数，用任意 ThemePaint 都能验。
 *
 * 注意 `chat_outBubble` / `chat_outText` 有兜底：发送气泡规格缺失时
 * 分别回落到 `colorScheme.primary` 与 `Color.White`——不兜底会 NPE。
 */

internal fun slotDefaultColor(paint: com.maodouchat.theme.ThemePaint, slot: String): Color {
    return when (slot) {
        "accent" -> paint.colorScheme.primary
        "chat_background" -> paint.chatPalette.chatBackground
        "chat_inBubble" -> paint.chatPalette.chatBubbleReceived
        "chat_inText" -> paint.chatPalette.textPrimary
        "chat_outBubble" -> paint.sentBubbleSpec?.color ?: paint.colorScheme.primary
        "chat_outText" -> paint.sentBubbleSpec?.content ?: Color.White
        "text_primary" -> paint.chatPalette.textPrimary
        "input_background" -> paint.chatPalette.chatInputBackground
        "unread_badge" -> paint.chatPalette.unreadRed
        "system_message" -> paint.chatPalette.systemMessageBackground
        else -> paint.colorScheme.background
    }
}

/**
 * 屏幕层解析：先吃 [ThemeFileColorPolicy.parseAtTheme] 的 TG 键，再补原生槽位名
 * （`parseAtTheme` 只认 TG_KEY_MAP，导出/手写 native slot 会被丢掉）。
 */

internal fun parseThemeFile(text: String): Map<String, Color> =
    ThemeFileColorPolicy.parseThemeFile(text).mapValues { Color(it.value) }

internal fun parseHexColor(raw: String): Color? =
    ThemeFileColorPolicy.parseHexArgb(raw)?.let { Color(it) }
