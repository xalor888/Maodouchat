package com.maodouchat.domain.messaging

// .attheme 文本的颜色解析与格式化：返回 ARGB Int，调用方再包 Color(argb)。
// 槽位词汇表与 ThemeSlotKind 相同；TG 键映射只覆盖能对应到设计系统的核心键。
object ThemeFileColorPolicy {
    val SLOTS = listOf(
        "accent",
        "chat_background",
        "chat_inBubble",
        "chat_inText",
        "chat_outBubble",
        "chat_outText",
        "text_primary",
        "window_background",
        "input_background",
        "unread_badge",
        "system_message"
    )

    private val TG_KEY_MAP = mapOf(
        "actionBarDefaultAction" to "accent",
        "actionBarDefaultIcon" to "accent",
        "chat_inBubble" to "chat_inBubble",
        "chat_inTextColor" to "chat_inText",
        "chat_messagePanelBackground" to "chat_background",
        "chat_messagePanelBackground2" to "input_background",
        "chat_outBubble" to "chat_outBubble",
        "chat_outTextColor" to "chat_outText",
        "chat_unreadCounter" to "unread_badge",
        "chat_serviceMessageBackground" to "system_message",
        "chat_wallpaper" to "chat_background",
        "windowBackgroundWhite" to "window_background",
        "windowBackgroundWhiteBlackText" to "text_primary"
    )

    private val EXPORT_KEY_MAP = TG_KEY_MAP.entries.groupBy { it.value }.mapValues { it.value.first().key }

    fun exportKeyForSlot(slot: String): String? = EXPORT_KEY_MAP[slot]

    fun parseColorValue(value: String): Int? = runCatching {
        when {
            value.startsWith("#") -> {
                val hex = value.removePrefix("#")
                when (hex.length) {
                    6 -> ("FF$hex").toLong(16).toInt()
                    8 -> hex.toLong(16).toInt()
                    else -> null
                }
            }
            else -> value.toInt()
        }
    }.getOrNull()

    fun parseHexArgb(raw: String): Int? {
        val hex = raw.trim().removePrefix("#").filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        return runCatching {
            when (hex.length) {
                6 -> ("FF$hex").toLong(16).toInt()
                8 -> hex.toLong(radix = 16).toInt()
                else -> null
            }
        }.getOrNull()
    }

    fun parseAtTheme(text: String): Map<String, Int> {
        val out = HashMap<String, Int>()
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("//")) return@forEach
            val eq = trimmed.indexOf('=')
            if (eq <= 0) return@forEach
            val tgKey = trimmed.substring(0, eq).trim()
            val slot = TG_KEY_MAP[tgKey] ?: return@forEach
            val value = trimmed.substring(eq + 1).trim()
            val color = parseColorValue(value) ?: return@forEach
            out[slot] = color
        }
        return out
    }

    fun parseThemeFile(text: String): Map<String, Int> {
        val out = LinkedHashMap(parseAtTheme(text))
        val known = SLOTS.toSet()
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("//")) return@forEach
            val eq = trimmed.indexOf('=')
            if (eq <= 0) return@forEach
            val key = trimmed.substring(0, eq).trim()
            if (key !in known || out.containsKey(key)) return@forEach
            val value = trimmed.substring(eq + 1).trim()
            val color = parseHexArgb(value) ?: value.toIntOrNull()
            if (color != null) out[key] = color
        }
        return out
    }

    fun formatArgb(argb: Int): String = String.format("#%08X", argb)
}
