package com.maodouchat.domain.messaging

// 主题工作台微件 id → 种类；中文标题/副标题留在 app 侧，domain 只持种类。
enum class ThemeWorkbenchWidgetKind {
    BUBBLE, PROFILE, ACTIONS, STATS, CUSTOM
}

fun themeWorkbenchWidgetKind(id: String): ThemeWorkbenchWidgetKind = when (id) {
    "bubble" -> ThemeWorkbenchWidgetKind.BUBBLE
    "profile" -> ThemeWorkbenchWidgetKind.PROFILE
    "actions" -> ThemeWorkbenchWidgetKind.ACTIONS
    "stats" -> ThemeWorkbenchWidgetKind.STATS
    else -> ThemeWorkbenchWidgetKind.CUSTOM
}
