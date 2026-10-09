package com.maodouchat.ai

import com.maodouchat.ai.AiWritingStylePolicy.MAX_CUSTOM_CHARS
import com.maodouchat.ai.AiWritingStylePolicy.Preset
import com.maodouchat.ai.AiWritingStylePolicy.Snapshot

// 归一化簇：预设 id 解析、自定义文本清洗、Snapshot 装配。原 AiWritingStylePolicy 逻辑逐字搬入。
internal object AiWritingStyleNormalization {
    // 空白折叠正则：提到对象级复用，避免每次调用重复编译。
    private val styleTextWhitespaceRegex = Regex("\\s+")

    fun normalizeCustomNote(raw: String?): String =
        raw.orEmpty().trim().replace(styleTextWhitespaceRegex, " ").take(MAX_CUSTOM_CHARS)

    fun normalize(enabled: Boolean, presetId: String?, customNote: String?): Snapshot {
        val preset = Preset.fromId(presetId)
        val note = normalizeCustomNote(customNote)
        if (!enabled) {
            return Snapshot(enabled = false, preset = Preset.NONE, customNote = "")
        }
        // Enabled but empty preset+note still allowed (user can fill later); rewrite gets no extra hint.
        return Snapshot(enabled = true, preset = preset, customNote = note)
    }

    fun clear(): Snapshot = Snapshot(enabled = false, preset = Preset.NONE, customNote = "")
}
