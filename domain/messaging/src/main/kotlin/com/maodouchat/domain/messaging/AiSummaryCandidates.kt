package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiSummaryScope
import com.maodouchat.core.model.MAX_AI_SUMMARY_MESSAGES
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.SEVEN_DAYS_MS
import com.maodouchat.core.model.THIRTY_DAYS_MS
import com.maodouchat.core.model.aiSummaryContextText
import java.util.Calendar

// AI 消息总结的候选消息筛选：按范围（最近 / 今天 / 7 天 / 30 天 / 搜索结果 / 未读）
// 选出参与总结的消息。已由 AI 生成的消息（aiAssisted）会被排除，避免总结套娃。
// 由 app 侧 ChatDetailViewModel.summaryCandidates 逐字迁入；nowMillis 仅为可测性注入，默认当前时间。
fun summaryCandidates(
    scope: AiSummaryScope,
    searchResultIds: List<String>,
    sourceMessages: List<Message>,
    nowMillis: Long = System.currentTimeMillis()
): List<Message> {
    val todayStart = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val searchIds = searchResultIds.filter(String::isNotBlank).toHashSet()
    return sourceMessages
        .asSequence()
        .filterNot { it.parsedMeta().aiAssisted }
        .filter { it.aiSummaryContextText().isNotBlank() }
        .filter { message ->
            when (scope) {
                AiSummaryScope.RECENT -> true
                AiSummaryScope.TODAY -> message.timestamp >= todayStart
                AiSummaryScope.SEVEN_DAYS -> message.timestamp >= nowMillis - SEVEN_DAYS_MS
                AiSummaryScope.THIRTY_DAYS -> message.timestamp >= nowMillis - THIRTY_DAYS_MS
                AiSummaryScope.SEARCH_RESULTS -> message.id in searchIds
                AiSummaryScope.UNREAD -> false
            }
        }
        .sortedBy(Message::timestamp)
        .toList()
        .takeLast(if (scope == AiSummaryScope.RECENT) 24 else MAX_AI_SUMMARY_MESSAGES)
}
