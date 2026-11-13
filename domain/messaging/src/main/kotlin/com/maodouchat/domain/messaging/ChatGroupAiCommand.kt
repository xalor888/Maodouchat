package com.maodouchat.domain.messaging

// 群助手模式推断与 @AI 命令解析：纯字符串判定，自 app 侧 ChatDetailGroupAi.kt 逐字平移。
fun inferGroupAiMode(query: String): String {
    val normalized = query.trim().lowercase()
    return when {
        normalized.startsWith("总结") || normalized.startsWith("概括") || normalized.startsWith("summary") || normalized.startsWith("summarize") -> "summary"
        normalized.startsWith("决策") || normalized.startsWith("决定") || normalized.startsWith("decisions") -> "decisions"
        normalized.startsWith("待办") || normalized.startsWith("任务") || normalized.startsWith("tasks") || normalized.startsWith("todo") -> "tasks"
        normalized.startsWith("时间线") || normalized.startsWith("时间轴") || normalized.startsWith("timeline") || normalized.startsWith("chronology") -> "timeline"
        normalized.startsWith("风险") || normalized.startsWith("隐患") || normalized.startsWith("risk") || normalized.startsWith("blocker") -> "risks"
        else -> "answer"
    }
}

fun parseGroupAiCommand(value: String): String? {
    val trimmed = value.trim()
    if (!trimmed.startsWith("@AI", ignoreCase = true)) return null
    if (trimmed.length > 3 && !trimmed[3].isWhitespace()) return null
    return trimmed.drop(3).trim()
}
