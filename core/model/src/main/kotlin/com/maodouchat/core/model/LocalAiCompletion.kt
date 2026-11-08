package com.maodouchat.core.model

/** 本地 AI 模型调用的统一结果：纯文本、工具调用或失败。 */
sealed interface LocalAiCompletion {
    data class Text(val content: String) : LocalAiCompletion
    data class Tools(val calls: List<AgentToolCall>, val content: String) : LocalAiCompletion
    data class Error(val message: String) : LocalAiCompletion
}
