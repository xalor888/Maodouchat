package com.maodouchat.core.model

// 单轮文本任务改写指令，不经过工具轮次；建议/摘要/群助手/翻译四项已迁入 :domain:messaging 的 AgentTextTaskPolicy。
fun agentRewriteInstruction(mode: String, targetLanguage: String?): String {
    val safe = when (mode.trim().lowercase()) {
        "shorten" -> "缩短，保留原意"
        "formal" -> "更正式礼貌"
        "gentle" -> "更温和"
        "casual" -> "更口语"
        "professional" -> "更专业商务"
        "expand" -> "稍加展开，不编造事实"
        "bullet" -> "改成简洁条目"
        "clarify" -> "更清楚，不改变立场"
        "translate" -> "翻译成 ${targetLanguage?.trim().orEmpty().ifBlank { "中文" }}，只输出译文"
        else -> "润色，保持原意和语气"
    }
    return "改写下面的草稿：$safe。只输出改写结果，不要解释。"
}
