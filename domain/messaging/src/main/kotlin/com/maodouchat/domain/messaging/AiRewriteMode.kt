package com.maodouchat.domain.messaging

// AI 改写模式归一化：白名单内原样返回，其余回退 polish。自 app 侧 ChatDetailAiIntents.kt 逐字平移。
fun normalizeAiRewriteMode(mode: String?): String {
    val m = mode?.trim()?.lowercase().orEmpty()
    return when (m) {
        "polish", "shorten", "formal", "gentle", "casual",
        "professional", "expand", "bullet", "translate", "clarify" -> m
        else -> "polish"
    }
}
