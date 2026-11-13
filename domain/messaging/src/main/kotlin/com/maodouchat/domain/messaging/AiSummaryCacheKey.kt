package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiSummaryScope

// AI 摘要风格归一化与手动缓存键：自 app 侧 ChatDetailAiSummary.kt
//（summarizeMessages 内联逻辑）与 ChatDetailAiResults.kt
//（summaryScopeFromCacheKey）逐字平移，仅去 receiver / 抽成顶层函数。
fun normalizeAiSummaryStyle(style: String, scope: AiSummaryScope): String {
    val normalized = style.trim().lowercase()
    return when (normalized) {
        "brief", "detailed", "decisions", "tasks", "timeline", "risks" -> normalized
        else -> if (scope == AiSummaryScope.RECENT) "brief" else "detailed"
    }
}

fun buildAiSummaryCacheKey(
    chatId: String,
    scope: AiSummaryScope,
    style: String,
    firstMessageId: String,
    lastMessageId: String,
    messageCount: Int,
): String = "manual:$chatId:${scope.name}:$style:$firstMessageId:$lastMessageId:$messageCount"

fun summaryScopeFromCacheKey(cacheKey: String): AiSummaryScope {
    if (!cacheKey.startsWith("manual:")) return AiSummaryScope.UNREAD
    return cacheKey.split(':').getOrNull(2)
        ?.let { value -> AiSummaryScope.entries.firstOrNull { it.name == value } }
        ?: AiSummaryScope.RECENT
}
