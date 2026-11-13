package com.maodouchat.domain.messaging

// AI 意图归一化：回复语气与群助手模式。自 app 侧
// ChatDetailAiIntents.kt（requestAiSuggestions / requestGroupAiAssistant）逐字平移。
fun normalizeAiReplyTone(tone: String): String {
    return when (tone.trim().lowercase()) {
        "natural", "friendly", "formal", "concise", "warm", "humorous", "direct", "empathetic", "encouraging" -> tone.trim().lowercase()
        else -> "friendly"
    }
}

fun resolveGroupAiMode(mode: String?, query: String): String {
    return when (mode?.trim()?.lowercase()) {
        "answer", "summary", "decisions", "tasks", "timeline", "risks" -> mode.trim().lowercase()
        else -> inferGroupAiMode(query)
    }
}
