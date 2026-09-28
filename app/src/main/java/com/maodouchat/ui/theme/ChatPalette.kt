package com.maodouchat.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import com.maodouchat.theme.ChatPalette

val LocalChatPalette = compositionLocalOf { LightChatPalette }

val LightChatPalette = ChatPalette(
    chatBackground = Color(0xFFF7F8FA),
    chatBubbleReceived = Color(0xFFEEEEF0),
    chatBubbleReceivedBorder = Color(0xFFE4E4E6),
    chatInputBackground = Color(0xFFEEEEF0),
    chatInputBorder = Color(0xFFE4E4E6),
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
    chatBackground = Color(0xFF121214),
    chatBubbleReceived = Color(0xFF1E1E20),
    chatBubbleReceivedBorder = Color(0xFF28282B),
    chatInputBackground = Color(0xFF1E1E20),
    chatInputBorder = Color(0xFF2E2E32),
    chatInputPlaceholder = Color(0xFF8E8E93),
    systemMessageBackground = Color(0x992A2A2A),
    systemMessageText = Color(0xFFF2F2F2),
    textHint = Color(0xFF8E8E93),
    textPrimary = Color(0xFFF2F2F2),
    textSecondary = Color(0xFFA0A0A5),
    divider = Color(0xFF242426),
    unreadRed = UnreadRedDark,
    onlineGreen = OnlineGreenDark,
    chatElevatedSurface = Color(0xFF1C1C1E),
    chatElevatedSurfaceHigh = Color(0xFF2C2C2E)
)
