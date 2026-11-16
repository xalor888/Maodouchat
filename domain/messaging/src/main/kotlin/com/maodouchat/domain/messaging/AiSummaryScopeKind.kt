package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiSummaryScope

// AI 摘要范围种类；范围枚举本身在 :core:model，domain 只做 1:1 映射。
enum class AiSummaryScopeKind {
    RECENT,
    TODAY,
    SEVEN_DAYS,
    THIRTY_DAYS,
    SEARCH_RESULTS,
    UNREAD,
}

fun aiSummaryScopeKind(scope: AiSummaryScope): AiSummaryScopeKind =
    when (scope) {
        AiSummaryScope.RECENT -> AiSummaryScopeKind.RECENT
        AiSummaryScope.TODAY -> AiSummaryScopeKind.TODAY
        AiSummaryScope.SEVEN_DAYS -> AiSummaryScopeKind.SEVEN_DAYS
        AiSummaryScope.THIRTY_DAYS -> AiSummaryScopeKind.THIRTY_DAYS
        AiSummaryScope.SEARCH_RESULTS -> AiSummaryScopeKind.SEARCH_RESULTS
        AiSummaryScope.UNREAD -> AiSummaryScopeKind.UNREAD
    }
