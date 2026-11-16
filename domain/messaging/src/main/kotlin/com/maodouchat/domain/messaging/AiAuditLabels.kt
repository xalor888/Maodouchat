package com.maodouchat.domain.messaging

// AI 审计日志功能/状态→文案种类；R.string 与颜色映射留在 app 侧。
enum class AiFeatureLabelKind {
    Rewrite,
    SuggestReplies,
    Summarize,
    TranscribeVoice,
    TranslateMessage,
    SemanticSearch,
    GlobalSemanticSearch,
    GroupAssistant,
    ImageAnalyze,
    FileAnalyze,
    Generic,
    Raw,
}

fun aiFeatureLabelKind(feature: String): AiFeatureLabelKind = when (feature.lowercase()) {
    "rewrite" -> AiFeatureLabelKind.Rewrite
    "suggest_replies" -> AiFeatureLabelKind.SuggestReplies
    "summarize" -> AiFeatureLabelKind.Summarize
    "transcribe_voice" -> AiFeatureLabelKind.TranscribeVoice
    "translate_message" -> AiFeatureLabelKind.TranslateMessage
    "semantic_search" -> AiFeatureLabelKind.SemanticSearch
    "global_semantic_search" -> AiFeatureLabelKind.GlobalSemanticSearch
    "group_assistant" -> AiFeatureLabelKind.GroupAssistant
    "image_analyze" -> AiFeatureLabelKind.ImageAnalyze
    "file_analyze" -> AiFeatureLabelKind.FileAnalyze
    else -> if (feature.isBlank()) AiFeatureLabelKind.Generic else AiFeatureLabelKind.Raw
}

enum class AiStatusLabelKind {
    Success,
    Failed,
    Disabled,
    RateLimited,
    Unknown,
    Raw,
}

fun aiStatusLabelKind(status: String): AiStatusLabelKind = when (status.lowercase()) {
    "success" -> AiStatusLabelKind.Success
    "failed", "error" -> AiStatusLabelKind.Failed
    "disabled" -> AiStatusLabelKind.Disabled
    "rate_limited" -> AiStatusLabelKind.RateLimited
    else -> if (status.isBlank()) AiStatusLabelKind.Unknown else AiStatusLabelKind.Raw
}
