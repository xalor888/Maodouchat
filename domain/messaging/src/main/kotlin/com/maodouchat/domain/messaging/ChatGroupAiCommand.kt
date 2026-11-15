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
// 模式 -> 芯片插入前缀（@AI 命令条点芯片时回写的中文词），纯映射。
fun groupAiModePrefix(mode: String): String = when (mode) {
    "summary" -> "总结 "
    "decisions" -> "决策 "
    "tasks" -> "待办 "
    "timeline" -> "时间线 "
    "risks" -> "风险 "
    else -> ""
}

// 点芯片时从查询里剥掉已匹配的模式关键词，只剥总结/决策/待办三档：
// 时间线/风险与无模式分支原样返回（与 app 侧旧行为逐字一致）。
fun stripGroupAiModeKeyword(query: String): String {
    val lower = query.lowercase()
    return when {
        query.startsWith("总结") || query.startsWith("概括") ->
            query.removePrefix("总结").removePrefix("概括").trimStart()
        lower.startsWith("summary") -> query.drop(7).trimStart()
        lower.startsWith("summarize") -> query.drop(9).trimStart()
        query.startsWith("决策") || query.startsWith("决定") ->
            query.removePrefix("决策").removePrefix("决定").trimStart()
        lower.startsWith("decisions") -> query.drop(9).trimStart()
        query.startsWith("待办") || query.startsWith("任务") ->
            query.removePrefix("待办").removePrefix("任务").trimStart()
        lower.startsWith("tasks") -> query.drop(5).trimStart()
        lower.startsWith("todo") -> query.drop(4).trimStart()
        else -> query
    }
}
