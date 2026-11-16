package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiSummaryScope

// AI 摘要范围选项：SEARCH_RESULTS 只有搜到结果时可选；描述种类由 app 侧再映射到文案，图标留在 app 侧。
object AiSummaryScopeOptionPolicy {

    fun isEnabled(scope: AiSummaryScope, searchResultCount: Int): Boolean =
        scope != AiSummaryScope.SEARCH_RESULTS || searchResultCount > 0

    fun descriptionKind(scope: AiSummaryScope, searchResultCount: Int): AiSummaryScopeDescriptionKind =
        when (scope) {
            AiSummaryScope.RECENT -> AiSummaryScopeDescriptionKind.RECENT
            AiSummaryScope.TODAY -> AiSummaryScopeDescriptionKind.TODAY
            AiSummaryScope.SEVEN_DAYS -> AiSummaryScopeDescriptionKind.WEEK
            AiSummaryScope.THIRTY_DAYS -> AiSummaryScopeDescriptionKind.MONTH
            AiSummaryScope.SEARCH_RESULTS ->
                if (searchResultCount > 0) AiSummaryScopeDescriptionKind.SEARCH_COUNT
                else AiSummaryScopeDescriptionKind.SEARCH_EMPTY
            AiSummaryScope.UNREAD -> AiSummaryScopeDescriptionKind.UNREAD_EMPTY
        }
}

enum class AiSummaryScopeDescriptionKind {
    RECENT,
    TODAY,
    WEEK,
    MONTH,
    SEARCH_COUNT,
    SEARCH_EMPTY,
    UNREAD_EMPTY,
}
