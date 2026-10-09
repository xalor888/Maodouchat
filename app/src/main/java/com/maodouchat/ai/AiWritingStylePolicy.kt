package com.maodouchat.ai

/**
 * 写作风格偏好（M5-6）：可选、默认关、账号隔离、用户可见/可删。
 * 仅影响改写类提示附加说明；不上传独立记忆库，内容只存本机 prefs。
 */
// 写作风格策略门面：类型与常量保留原位置供调用方/测试引用，实现已按簇拆到
// AiWritingStyleNormalization（归一化）与 AiWritingStyleHintRendering（改写提示），这里只保留统一入口，全部透传，行为不变。
object AiWritingStylePolicy {
    const val MAX_CUSTOM_CHARS = 320
    const val MAX_PRESET_ID_CHARS = 40

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

    fun normalizeCustomNote(raw: String?): String =
        AiWritingStyleNormalization.normalizeCustomNote(raw)

    fun normalize(enabled: Boolean, presetId: String?, customNote: String?): Snapshot =
        AiWritingStyleNormalization.normalize(enabled, presetId, customNote)

    fun rewriteStyleHint(snapshot: Snapshot): String? =
        AiWritingStyleHintRendering.rewriteStyleHint(snapshot)

    fun clear(): Snapshot = AiWritingStyleNormalization.clear()
}
