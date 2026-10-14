package com.maodouchat.ui.screen.settings

import androidx.compose.ui.graphics.Color

/**
 * 9.260：快速配色预设（TG 社区热门配色一键应用）——每个预设是一组 槽位→颜色，
 * 浅色/深色各一套，点按应用到当前变体。
 */
internal data class ThemeQuickPreset(
    val nameRes: Int,
    val swatches: List<Color>,
    val slots: Map<String, Color>
)
