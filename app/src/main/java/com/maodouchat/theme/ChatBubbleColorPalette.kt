package com.maodouchat.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

// ─── 聊天气泡颜色（自定义主题色 · 仅影响聊天页发送气泡）────────
//
// 2026-09-28：分层门禁 FQ 盲区收口——纯数据类型从 `ui/theme` 迁到中立的 `theme/` 包
//（`util/ChatAppearancePreferences` 的气泡色读写依赖它，非 ui 包不再需要 FQ 引用 ui）。
// 类体逐行未动。
object ChatBubbleColorPalette {
    const val BLUE = "blue"
    const val GREEN = "green"
    const val PURPLE = "purple"
    const val ORANGE = "orange"
    const val PINK = "pink"
    const val TEAL = "teal"

    fun light(id: String): Color = when (id) {
        GREEN -> Color(0xFF34C759)
        PURPLE -> Color(0xFF8B5CF6)
        ORANGE -> Color(0xFFF97316)
        PINK -> Color(0xFFEC4899)
        TEAL -> Color(0xFF06B6D4)
        else -> Color(0xFF007AFF)
    }

    fun dark(id: String): Color = when (id) {
        GREEN -> Color(0xFF30D158)
        PURPLE -> Color(0xFFA78BFA)
        ORANGE -> Color(0xFFFF9F0A)
        PINK -> Color(0xFFF472B6)
        TEAL -> Color(0xFF22D3EE)
        else -> Color(0xFF0A84FF)
    }

    fun normalize(raw: String?): String {
        val id = raw?.trim()?.lowercase().orEmpty()
        return if (id in setOf(BLUE, GREEN, PURPLE, ORANGE, PINK, TEAL)) id else GREEN
    }

    /** 发送气泡渐变（浅色端略亮，保留原蓝色梯度观感）。 */
    fun gradient(base: Color): List<Color> {
        val lifted = runCatching {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(base.toArgb(), hsv)
            hsv[2] = (hsv[2] * 1.08f).coerceAtMost(1f)
            androidx.compose.ui.graphics.Color.hsv(hsv[0], hsv[1], hsv[2])
        }.getOrDefault(base)
        return listOf(base, lifted)
    }
}
