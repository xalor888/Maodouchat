package com.maodouchat.ui.screen.chatlist

import com.maodouchat.R

enum class GlobalSearchMode { KEYWORD, AI }

/** 搜索类型过滤 */
enum class SearchFilterType(val apiValue: String, val labelRes: Int) {
    ALL("ALL", R.string.search_filter_all),
    TEXT("TEXT", R.string.search_filter_text),
    IMAGE("IMAGE", R.string.search_filter_image),
    FILE("FILE", R.string.search_filter_file),
    VOICE("VOICE", R.string.search_filter_voice),
    VIDEO("VIDEO", R.string.search_filter_video),
    LINK("LINK", R.string.search_filter_link)
}

data class GlobalSearchHit(
    val chatId: String,
    val messageId: String,
    val chatName: String,
    val senderName: String,
    val text: String,
    val timestamp: Long,
    val semanticScore: Double? = null,
    val messageType: String = "TEXT"
)

data class GlobalSearchUiState(
    val query: String = "",
    val mode: GlobalSearchMode = GlobalSearchMode.KEYWORD,
    val filterType: SearchFilterType = SearchFilterType.ALL,
    val results: List<GlobalSearchHit> = emptyList(),
    val isIndexing: Boolean = true,
    val isSearching: Boolean = false,
    val showAiConsent: Boolean = false,
    val aiSearchCompleted: Boolean = false,
    val excludedChatCount: Int = 0,
    val error: String? = null,
    val recentSearches: List<String> = emptyList()
)
