package com.maodouchat.ui.screen.settings

import androidx.compose.ui.graphics.Color
import com.maodouchat.R

/**
 * 9.260：快速配色预设（TG 社区热门配色一键应用）——每个预设是一组 槽位→颜色，
 * 浅色/深色各一套，点按应用到当前变体。
 */
private data class ThemeQuickPreset(
    val nameRes: Int,
    val swatches: List<Color>,
    val slots: Map<String, Color>
)

internal val LIGHT_QUICK_PRESETS = listOf(
    ThemeQuickPreset(
        R.string.theme_preset_tg_green,
        listOf(Color(0xFFEFFDDE), Color(0xFFE7EBEE), Color(0xFF3390EC)),
        mapOf(
            "chat_outBubble" to Color(0xFFEFFDDE),
            "chat_outText" to Color(0xFF212121),
            "chat_inBubble" to Color(0xFFFFFFFF),
            "chat_background" to Color(0xFFE7EBEE),
            "accent" to Color(0xFF3390EC)
        )
    ),
    ThemeQuickPreset(
        R.string.theme_preset_tg_blue,
        listOf(Color(0xFF2D7ED5), Color(0xFFF0F0F0), Color(0xFFFFFFFF)),
        mapOf(
            "chat_outBubble" to Color(0xFF2D7ED5),
            "chat_outText" to Color(0xFFFFFFFF),
            "chat_inBubble" to Color(0xFFF0F0F0),
            "chat_background" to Color(0xFFFFFFFF),
            "accent" to Color(0xFF2D7ED5)
        )
    ),
    ThemeQuickPreset(
        R.string.theme_preset_sakura,
        listOf(Color(0xFFFFE1E8), Color(0xFFFFF5F7), Color(0xFFE8608A)),
        mapOf(
            "chat_outBubble" to Color(0xFFFFE1E8),
            "chat_outText" to Color(0xFF7A2E42),
            "chat_inBubble" to Color(0xFFFFFFFF),
            "chat_background" to Color(0xFFFFF5F7),
            "accent" to Color(0xFFE8608A)
        )
    ),
    ThemeQuickPreset(
        R.string.theme_preset_mint,
        listOf(Color(0xFFD8F5E3), Color(0xFFF0FAF4), Color(0xFF2FA36B)),
        mapOf(
            "chat_outBubble" to Color(0xFFD8F5E3),
            "chat_outText" to Color(0xFF1F5C3D),
            "chat_inBubble" to Color(0xFFFFFFFF),
            "chat_background" to Color(0xFFF0FAF4),
            "accent" to Color(0xFF2FA36B)
        )
    ),
    ThemeQuickPreset(
        R.string.theme_preset_cream,
        listOf(Color(0xFFFFF3D6), Color(0xFFFFFBF0), Color(0xFFD9A441)),
        mapOf(
            "chat_outBubble" to Color(0xFFFFF3D6),
            "chat_outText" to Color(0xFF7A5C1E),
            "chat_inBubble" to Color(0xFFFFFFFF),
            "chat_background" to Color(0xFFFFFBF0),
            "accent" to Color(0xFFD9A441)
        )
    )
)

internal val DARK_QUICK_PRESETS = listOf(
    ThemeQuickPreset(
        R.string.theme_preset_tg_night,
        listOf(Color(0xFF2B5278), Color(0xFF182533), Color(0xFF0E1621)),
        mapOf(
            "chat_outBubble" to Color(0xFF2B5278),
            "chat_outText" to Color(0xFFF5F8FA),
            "chat_inBubble" to Color(0xFF182533),
            "chat_background" to Color(0xFF0E1621),
            "accent" to Color(0xFF5288C1)
        )
    ),
    ThemeQuickPreset(
        R.string.theme_preset_amoled,
        listOf(Color(0xFF0A84FF), Color(0xFF1C1C1E), Color(0xFF000000)),
        mapOf(
            "chat_outBubble" to Color(0xFF0A84FF),
            "chat_outText" to Color(0xFFFFFFFF),
            "chat_inBubble" to Color(0xFF1C1C1E),
            "chat_background" to Color(0xFF000000),
            "window_background" to Color(0xFF000000),
            "accent" to Color(0xFF0A84FF)
        )
    ),
    ThemeQuickPreset(
        R.string.theme_preset_dark_violet,
        listOf(Color(0xFF5B3E8F), Color(0xFF2A2138), Color(0xFF16121F)),
        mapOf(
            "chat_outBubble" to Color(0xFF5B3E8F),
            "chat_outText" to Color(0xFFF2ECFA),
            "chat_inBubble" to Color(0xFF2A2138),
            "chat_background" to Color(0xFF16121F),
            "accent" to Color(0xFF9B6FD0)
        )
    ),
    ThemeQuickPreset(
        R.string.theme_preset_dark_forest,
        listOf(Color(0xFF1F5C3D), Color(0xFF17241C), Color(0xFF0D1512)),
        mapOf(
            "chat_outBubble" to Color(0xFF1F5C3D),
            "chat_outText" to Color(0xFFE4F5E9),
            "chat_inBubble" to Color(0xFF17241C),
            "chat_background" to Color(0xFF0D1512),
            "accent" to Color(0xFF4CAF7D)
        )
    )
)

