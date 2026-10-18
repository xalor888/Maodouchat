package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.maodouchat.data.model.Message

// 搜索派生量族：消息文档、本地关键词结果、语义候选与最终合并结果。
// 原在 ChatDetailRoute 内联声明，纯搬移不改判断。
internal class ChatDetailSearchDerived(
    val searchResults: List<Message>,
    val localSearchResults: List<Message>,
    val semanticCandidates: List<Message>,
)

@Composable
internal fun rememberChatDetailSearchDerived(
    messages: List<Message>,
    messagesById: Map<String, Message>,
    search: ChatDetailSearchState,
    semanticSearchResultIds: List<String>,
    semanticSearchQuery: String,
    currentUserId: String,
): ChatDetailSearchDerived {
    val searchDocuments = remember(messages) { buildChatSearchDocuments(messages) }
    val localSearchResults = remember(search.searchQuery, search.searchScope, search.searchWindow, searchDocuments) {
        searchChatDocuments(
            documents = searchDocuments,
            query = search.searchQuery,
            scope = search.searchScope,
            window = search.searchWindow,
            currentUserId = currentUserId
        )
    }
    val semanticCandidates = remember(search.showSearchBar, search.searchMode, search.searchScope, search.searchWindow, searchDocuments) {
        if (search.showSearchBar && search.searchMode == ChatSearchMode.SEMANTIC) {
            semanticSearchCandidates(searchDocuments, search.searchScope, search.searchWindow, currentUserId = currentUserId)
        } else {
            emptyList()
        }
    }
    val semanticSearchResults = remember(
        semanticSearchResultIds,
        semanticSearchQuery,
        search.searchQuery,
        messages
    ) {
        if (semanticSearchQuery != search.searchQuery.trim()) {
            emptyList()
        } else {
            semanticSearchResultIds.mapNotNull(messagesById::get)
        }
    }
    val searchResults = if (search.searchMode == ChatSearchMode.SEMANTIC) semanticSearchResults else localSearchResults
    return ChatDetailSearchDerived(
        searchResults = searchResults,
        localSearchResults = localSearchResults,
        semanticCandidates = semanticCandidates,
    )
}
