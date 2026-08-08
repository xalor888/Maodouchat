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
    chatBackground = ChatBackground,
    chatBubbleReceived = ChatBubbleReceived,
    chatBubbleReceivedBorder = ChatBubbleReceivedBorder,
    chatInputBackground = ChatInputBackground,
    chatInputBorder = ChatInputBorder,
    chatInputPlaceholder = ChatInputPlaceholder,
    systemMessageBackground = SystemMessageBg,
    systemMessageText = SystemMessageText,
    textHint = TextHint,
    textPrimary = TextPrimary,
    textSecondary = TextSecondary,
    divider = Divider,
    unreadRed = UnreadRed,
    onlineGreen = OnlineGreen,
    chatElevatedSurface = SurfaceContainerLow,
    chatElevatedSurfaceHigh = SurfaceContainerHigh
)

val DarkChatPalette = ChatPalette(
    chatBackground = ChatBackgroundDark,
    chatBubbleReceived = ChatBubbleReceivedDark,
    chatBubbleReceivedBorder = ChatBubbleReceivedBorderDark,
    chatInputBackground = ChatInputBackgroundDark,
    chatInputBorder = ChatInputBorderDark,
    chatInputPlaceholder = ChatInputPlaceholderDark,
    systemMessageBackground = SystemMessageBgDark,
    systemMessageText = SystemMessageTextDark,
    textHint = TextHintDark,
    textPrimary = TextPrimaryDark,
    textSecondary = TextSecondaryDark,
    divider = DividerDark,
    unreadRed = UnreadRedDark,
    onlineGreen = OnlineGreenDark,
    chatElevatedSurface = SurfaceContainerLowDark,
    chatElevatedSurfaceHigh = SurfaceContainerHighDark
)
