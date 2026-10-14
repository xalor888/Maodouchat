package com.maodouchat.util

/**
 * 聊天壁纸预设 + 消息字体档位（本地，不进 E2EE 明文）。
 */
enum class ChatWallpaperPreset(val id: String) {
    DEFAULT("default"),
    MINT("mint"),
    LAVENDER("lavender"),
    SAND("sand"),
    NIGHT("night"),
    ROSE("rose"),
    SKY("sky"),
    SLATE("slate"),
    PEACH("peach"),
    OLIVE("olive"),
    CORAL("coral"),
    PLUM("plum"),
    INDIGO("indigo"),
    AMBER("amber"),
    TEAL("teal"),
    GRAPHITE("graphite");

    companion object {
        fun fromId(raw: String?): ChatWallpaperPreset {
            val id = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.id == id } ?: DEFAULT
        }
    }
}

enum class ChatFontScale(val id: String, val multiplier: Float) {
    SMALL("small", 0.92f),
    NORMAL("normal", 1.0f),
    LARGE("large", 1.12f),
    XLARGE("xlarge", 1.24f),
    XXLARGE("xxlarge", 1.36f);

    companion object {
        fun fromId(raw: String?): ChatFontScale {
            val id = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.id == id } ?: NORMAL
        }
    }
}
