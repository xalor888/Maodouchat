package com.maodouchat.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 针对聊天页的「浅 / 深色」可识别的设计系统：保持品牌色，强调易读性。
 *
 * 使用方式：`val palette = LocalChatPalette.current; Box(modifier = Modifier.background(palette.chatBackground))`
 * 这样 Compose 组件能根据 `MaodouchatTheme` 当前的 light/dark 自动切换。
 */
data class ChatPalette(
    val chatBackground: Color,
    val chatBubbleReceived: Color,
    val chatBubbleReceivedBorder: Color,
    val chatInputBackground: Color,
    val chatInputBorder: Color,
    val chatInputPlaceholder: Color,
    val systemMessageBackground: Color,
    val systemMessageText: Color,
    val textHint: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val divider: Color,
    val unreadRed: Color,
    val onlineGreen: Color,
    /** Subtle elevated surface (input bar / pinned banner) — reuses Material tonal tokens for depth. */
    val chatElevatedSurface: Color,
    /** Higher elevated surface (FAB / overlay sheets) — reuses Material tonal tokens for depth. */
    val chatElevatedSurfaceHigh: Color
)

val LocalChatPalette = compositionLocalOf { LightChatPalette }

val LightChatPalette = ChatPalette(
    chatBackground = Color(0xFFFFFFFF),
    chatBubbleReceived = Color(0xFFF2F2F2),
    chatBubbleReceivedBorder = Color.Transparent,
    chatInputBackground = Color(0xFFF7F7F7),
    chatInputBorder = Color(0xFFE0E0E0),
    chatInputPlaceholder = Color(0xFF8A9099),
    systemMessageBackground = Color(0x99F2F2F2),
    systemMessageText = Color(0xFF1A1A1A),
    textHint = Color(0xFF8A9099),
    textPrimary = Color(0xFF1A1A1A),
    textSecondary = Color(0xFF5C6370),
    divider = Color(0xFFE0E0E0),
    unreadRed = UnreadRed,
    onlineGreen = OnlineGreen,
    chatElevatedSurface = Color(0xFFF7F7F7),
    chatElevatedSurfaceHigh = Color(0xFFEEEEEE)
)

val DarkChatPalette = ChatPalette(
    chatBackground = Color(0xFF111111),
    chatBubbleReceived = Color(0xFF2A2A2A),
    chatBubbleReceivedBorder = Color.Transparent,
    chatInputBackground = Color(0xFF1A1A1A),
    chatInputBorder = Color(0xFF2A2A2A),
    chatInputPlaceholder = Color(0xFFC5CCD4),
    systemMessageBackground = Color(0x992A2A2A),
    systemMessageText = Color(0xFFF2F2F2),
    textHint = Color(0xFFC5CCD4),
    textPrimary = Color(0xFFF2F2F2),
    textSecondary = Color(0xFFC5CCD4),
    divider = Color(0xFF2A2A2A),
    unreadRed = UnreadRedDark,
    onlineGreen = OnlineGreenDark,
    chatElevatedSurface = Color(0xFF1A1A1A),
    chatElevatedSurfaceHigh = Color(0xFF2A2A2A)
)
