package com.maodouchat.ai

private const val MAX_PRESET_ID_CHARS = 40

enum class Preset(val id: String) {
    NONE("none"),
    CONCISE("concise"),
    FORMAL("formal"),
    WARM("warm"),
    PROFESSIONAL("professional"),
    CASUAL("casual"),
    WITTY("witty"),
    EMPATHETIC("empathetic"),
    DIRECT("direct"),
    ENTHUSIASTIC("enthusiastic"),
    DIPLOMATIC("diplomatic");

    companion object {
        fun fromId(raw: String?): Preset {
            val id = raw?.trim()?.take(MAX_PRESET_ID_CHARS)?.lowercase().orEmpty()
            return entries.firstOrNull { it.id == id } ?: NONE
        }
    }
}

data class Snapshot(
    val enabled: Boolean = false,
    val preset: Preset = Preset.NONE,
    val customNote: String = ""
) {
    val hasMemorableContent: Boolean
        get() = enabled && (preset != Preset.NONE || customNote.isNotBlank())
}
