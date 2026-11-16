package com.maodouchat.domain.conversation

import com.maodouchat.core.model.GlobalSearchMode

// 全局搜索内容态：优先级与 app 侧原 when 一致，空查询 > 加载中 > 失败空态 > AI 就绪/无结果 > 结果列表。
enum class GlobalSearchContentKind {
    RECENT_OR_INITIAL,
    LOADING,
    ERROR_EMPTY,
    AI_READY,
    NO_RESULTS,
    RESULTS,
}

fun globalSearchContentKind(
    queryBlank: Boolean,
    isIndexing: Boolean,
    isSearching: Boolean,
    resultsEmpty: Boolean,
    hasError: Boolean,
    mode: GlobalSearchMode,
    aiSearchCompleted: Boolean
): GlobalSearchContentKind = when {
    queryBlank -> GlobalSearchContentKind.RECENT_OR_INITIAL
    isIndexing || isSearching -> GlobalSearchContentKind.LOADING
    resultsEmpty && hasError -> GlobalSearchContentKind.ERROR_EMPTY
    resultsEmpty && mode == GlobalSearchMode.AI && !aiSearchCompleted ->
        GlobalSearchContentKind.AI_READY
    resultsEmpty -> GlobalSearchContentKind.NO_RESULTS
    else -> GlobalSearchContentKind.RESULTS
}
