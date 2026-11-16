package com.maodouchat.domain.messaging

// AI 状态条可见性与四路优先级分派，逐字平移自 app 侧 ChatDetailAiStatusStrip。
// 分支互斥，when 自上而下取第一个命中，顺序错了 UI 就会串味。
enum class AiStatusStripMode {
    STREAMING,
    WORKING,
    SUGGESTIONS,
    ERROR,
}

// 草稿流式输出由 AiDraftStreamBar 单独渲染，这里必须排除，否则两条进度条同时出现。
fun aiStatusStripVisible(
    isAiWorking: Boolean,
    isAiDraftStreaming: Boolean,
    aiSuggestions: List<String>,
    aiReplyStreamErrorCode: String?,
): Boolean = (isAiWorking && !isAiDraftStreaming) ||
    aiSuggestions.isNotEmpty() ||
    aiReplyStreamErrorCode != null

fun aiStatusStripMode(
    isAiReplyStreaming: Boolean,
    isAiWorking: Boolean,
    aiSuggestions: List<String>,
): AiStatusStripMode = when {
    isAiReplyStreaming -> AiStatusStripMode.STREAMING
    isAiWorking -> AiStatusStripMode.WORKING
    aiSuggestions.isNotEmpty() -> AiStatusStripMode.SUGGESTIONS
    else -> AiStatusStripMode.ERROR
}
