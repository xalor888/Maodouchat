package com.maodouchat.ai

// 注入意图检测簇：纯字符串模板匹配，命中任一模板即视为注入尝试。
internal object AiPromptInjectionDetection {

    fun isLikelyInjectionAttempt(text: String?): Boolean {
        val body = text.orEmpty()
        if (body.isBlank()) return false
        val lower = body.lowercase()
        return lower.contains("ignore previous") ||
            lower.contains("ignore all previous") ||
            lower.contains("disregard previous") ||
            lower.contains("忽略以上") ||
            lower.contains("忽略之前") ||
            lower.contains("忽略上述") ||
            lower.contains("你现在是系统") ||
            lower.contains("you are now the system") ||
            lower.contains("developer mode")
    }
}
