package com.maodouchat.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 主题对「发送气泡」的接管规格：TG 各主题的发送气泡有专属配色（如经典浅色的 #EFFDDE 绿气泡
 * 需配深色文字）。null 表示沿用用户自选气泡色 + 白色文字（品牌默认）。
 *
 * 2026-09-28：分层门禁 FQ 盲区收口——纯数据类型从 `ui/theme` 迁到中立的 `theme/` 包。
 * 类体逐行未动。
 */
data class SentBubbleSpec(
    val color: Color,
    val content: Color,
    val contentSecondary: Color
)

/**
 * 一组完整的主题绘制参数：Material 色板 + 聊天调色板 + 发送气泡规格。
 *
 * 2026-09-28：分层门禁 FQ 盲区收口——纯数据类型从 `ui/theme` 迁到中立的 `theme/` 包
 *（`util/CustomThemeStore.applyOverrides` 的出入参类型，非 ui 包不再需要 FQ 引用 ui）。
 * 类体逐行未动。
 */
data class ThemePaint(
    val colorScheme: ColorScheme,
    val chatPalette: ChatPalette,
    val sentBubbleSpec: SentBubbleSpec?
)
