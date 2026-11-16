package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ArchiveSuggestion

// 归档建议卡片只展示前 3 条。
object ArchiveSuggestionDisplayPolicy {

    const val PREVIEW_LIMIT: Int = 3

    fun preview(suggestions: List<ArchiveSuggestion>): List<ArchiveSuggestion> =
        suggestions.take(PREVIEW_LIMIT)
}
