package com.maodouchat.domain.messaging

// AI 流式错误码对应的提示文案种类，domain 只定种类，真正的本地化字符串由 app 侧映射提供。
enum class AiStreamStatusText {
    CANCELLED,
    RATE_LIMITED,
    QUOTA_EXCEEDED,
    NETWORK,
    OUTCOME_UNKNOWN,
    SERVER,
    INVALID_RESULT,
    FAILED,
}

// 三处多码一支是有意的：TIMEOUT / OUTCOME_UNKNOWN / UNKNOWN 的用户动作相同（确认后手动重试），
// EMPTY_RESULT / INVALID_RESPONSE 都是“未返回有效结果”，文案分开只会让人以为区别对待。
fun resolveAiStreamStatusText(base: String): AiStreamStatusText = when (base) {
    "CANCELLED" -> AiStreamStatusText.CANCELLED
    "RATE_LIMITED" -> AiStreamStatusText.RATE_LIMITED
    "QUOTA_EXCEEDED" -> AiStreamStatusText.QUOTA_EXCEEDED
    "NETWORK" -> AiStreamStatusText.NETWORK
    "TIMEOUT", "OUTCOME_UNKNOWN", "UNKNOWN" -> AiStreamStatusText.OUTCOME_UNKNOWN
    "SERVER" -> AiStreamStatusText.SERVER
    "EMPTY_RESULT", "INVALID_RESPONSE" -> AiStreamStatusText.INVALID_RESULT
    else -> AiStreamStatusText.FAILED
}
