package com.maodouchat.domain.messaging

// 强调色 id → 种类；R.string 映射留在 app 侧；未知 id 按显示惯例归为青色。
enum class AccentColorKind {
    BLUE, GREEN, PURPLE, ORANGE, PINK, RED, TEAL
}

fun accentColorKind(id: String): AccentColorKind = when (id) {
    "blue" -> AccentColorKind.BLUE
    "green" -> AccentColorKind.GREEN
    "purple" -> AccentColorKind.PURPLE
    "orange" -> AccentColorKind.ORANGE
    "pink" -> AccentColorKind.PINK
    "red" -> AccentColorKind.RED
    else -> AccentColorKind.TEAL
}
