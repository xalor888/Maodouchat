package com.maodouchat.util

/**
 * 语音转文字入口策略（纯函数）。
 * 复用既有 AI 转写；气泡一键触发；长文折叠。
 */
object VoiceTranscriptPolicy {
    /** 气泡预览最大字符数，超出可展开 */
    const val PREVIEW_MAX_CHARS = 360

    /** 与服务端/客户端缓存上限对齐 */
    const val MAX_TRANSCRIPT_LENGTH = 6_000

    fun normalize(raw: String?): String =
        raw.orEmpty().trim().take(MAX_TRANSCRIPT_LENGTH)

    fun hasTranscript(raw: String?): Boolean =
        normalize(raw).isNotEmpty()

    /**
     * 是否展示气泡上的「转文字」一键入口。
     * 有结果或正在转写时不展示（改显示结果区）。
     */
    fun shouldShowInlineEntry(
        isVoiceMessage: Boolean,
        transcript: String?,
        isTranscribing: Boolean
    ): Boolean {
        if (!isVoiceMessage) return false
        if (isTranscribing) return false
        return !hasTranscript(transcript)
    }

    fun needsExpandToggle(transcript: String?): Boolean =
        normalize(transcript).length > PREVIEW_MAX_CHARS

    fun displayText(transcript: String?, expanded: Boolean): String {
        val full = normalize(transcript)
        if (full.isEmpty()) return ""
        if (expanded || full.length <= PREVIEW_MAX_CHARS) return full
        return full.take(PREVIEW_MAX_CHARS).trimEnd() + "…"
    }

    /** 是否允许发起转写请求（入口侧守卫，业务侧仍需 AI 开关等） */
    fun canRequest(
        isVoiceMessage: Boolean,
        transcript: String?,
        isTranscribing: Boolean
    ): Boolean {
        if (!isVoiceMessage) return false
        if (isTranscribing) return false
        return !hasTranscript(transcript)
    }
}
