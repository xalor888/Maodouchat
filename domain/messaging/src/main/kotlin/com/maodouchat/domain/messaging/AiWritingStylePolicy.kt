package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Snapshot

// 写作风格偏好：可选、默认关、账号隔离；只影响改写类提示附加说明。
// 写作风格策略门面：类型已迁入 :core:model（AiWritingStylePolicyModels），实现已按簇拆到
// AiWritingStyleNormalization（归一化）与 AiWritingStyleHintRendering（改写提示），这里只保留常量与统一入口，全部透传，行为不变。
object AiWritingStylePolicy {
    const val MAX_CUSTOM_CHARS = 320

    fun normalizeCustomNote(raw: String?): String =
        AiWritingStyleNormalization.normalizeCustomNote(raw)

    fun normalize(enabled: Boolean, presetId: String?, customNote: String?): Snapshot =
        AiWritingStyleNormalization.normalize(enabled, presetId, customNote)

    fun rewriteStyleHint(snapshot: Snapshot): String? =
        AiWritingStyleHintRendering.rewriteStyleHint(snapshot)

    fun clear(): Snapshot = AiWritingStyleNormalization.clear()
}
