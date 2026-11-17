package com.maodouchat.domain.messaging

// 聊天气泡颜色 id 归一化；Color 映射仍在 app 侧。
object ChatBubbleColorPolicy {
    const val BLUE = "blue"
    const val GREEN = "green"
    const val PURPLE = "purple"
    const val ORANGE = "orange"
    const val PINK = "pink"
    const val TEAL = "teal"

    fun normalize(raw: String?): String {
        val id = raw?.trim()?.lowercase().orEmpty()
        return if (id in setOf(BLUE, GREEN, PURPLE, ORANGE, PINK, TEAL)) id else GREEN
    }
}
