package com.maodouchat.ui.screen.chatlist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.maodouchat.R
import com.maodouchat.data.model.Chat
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

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

class GlobalSearchViewModel(application: Application) : AndroidViewModel(application) {
    // U02 延伸：仓库入口收进非 ui 的 AppRepositories（不再从 Application 强转后自取 database）。
    internal val searchRepository = com.maodouchat.data.repository.AppRepositories.messageSearch
    internal val chatRepository = com.maodouchat.data.repository.AppRepositories.chats
    internal val _uiState = MutableStateFlow(GlobalSearchUiState())
    val uiState: StateFlow<GlobalSearchUiState> = _uiState.asStateFlow()

    internal var chatsById: Map<String, Chat> = emptyMap()
    internal var localSearchJob: Job? = null
    internal var pendingAiQuery: String? = null
    internal var generation = 0L

    init {
        refreshIndex()
        _uiState.update { it.copy(recentSearches = RecentSearches.load(getApplication())) }
    }

    internal fun text(id: Int): String = getApplication<Application>().getString(id)

    fun onQueryChange(value: String) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.GLOBAL_SEARCH)) {
            _uiState.update {
                it.copy(
                    query = value.take(400),
                    results = emptyList(),
                    isSearching = false,
                    error = text(R.string.global_search_disabled)
                )
            }
            return
        }
        val query = value.take(400)
        generation++
        localSearchJob?.cancel()
        _uiState.update {
            it.copy(
                query = query,
                results = emptyList(),
                isSearching = false,
                aiSearchCompleted = false,
                excludedChatCount = 0,
                error = null
            )
        }
        if (_uiState.value.mode == GlobalSearchMode.KEYWORD) scheduleLocalSearch(query)
    }

    fun setMode(mode: GlobalSearchMode) {
        if (_uiState.value.mode == mode) return
        generation++
        localSearchJob?.cancel()
        _uiState.update {
            it.copy(
                mode = mode,
                results = emptyList(),
                isSearching = false,
                aiSearchCompleted = false,
                excludedChatCount = 0,
                error = null
            )
        }
        // AI mode requires explicit confirm via requestAiSearch — never auto-run.
        if (mode == GlobalSearchMode.KEYWORD) scheduleLocalSearch(_uiState.value.query)
    }

    fun setFilterType(filterType: SearchFilterType) {
        if (_uiState.value.filterType == filterType) return
        _uiState.update { it.copy(filterType = filterType) }
        if (_uiState.value.mode == GlobalSearchMode.KEYWORD && _uiState.value.query.isNotBlank()) {
            scheduleLocalSearch(_uiState.value.query)
        }
    }

    fun useRecentSearch(query: String) {
        if (query.isBlank()) return
        onQueryChange(query)
    }

    fun clearRecentSearches() {
        RecentSearches.clear(getApplication())
        _uiState.update { it.copy(recentSearches = emptyList()) }
    }

    private fun recordRecentSearch(query: String) {
        val trimmed = query.trim().take(100)
        if (trimmed.isBlank()) return
        val updated = RecentSearches.push(getApplication(), trimmed)
        _uiState.update { it.copy(recentSearches = updated) }
    }

    private companion object {
        val SERVER_MESSAGE_ID = Regex("^[A-Za-z0-9_-]{1,100}$")
    }
}
