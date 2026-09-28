package com.maodouchat.theme

import androidx.compose.ui.graphics.Color

/**
 * 针对聊天页的「浅 / 深色」可识别的设计系统：保持品牌色，强调易读性。
 *
 * 2026-09-28：分层门禁 FQ 盲区收口——纯数据类型从 `ui/theme` 迁到中立的 `theme/` 包
 * （`ThemePaint` 引用它，留在 ui 里会让中立包反向依赖 ui，门禁判据不允许）。
 * 类体逐行未动。
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
