package com.maodouchat.ai

/**
 * 写作风格偏好（M5-6）：可选、默认关、账号隔离、用户可见/可删。
 * 仅影响改写类提示附加说明；不上传独立记忆库，内容只存本机 prefs。
 */
// 写作风格策略门面：类型已提为同包顶层（AiWritingStylePolicyModels），实现已按簇拆到
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
