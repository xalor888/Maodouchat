package com.maodouchat.ui.screen.chatlist

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.data.local.entity.MessageSearchDocumentEntity
import com.maodouchat.data.model.Chat
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val searchRepository = com.maodouchat.data.repository.AppRepositories.messageSearch
    private val chatRepository = com.maodouchat.data.repository.AppRepositories.chats
    private val _uiState = MutableStateFlow(GlobalSearchUiState())
    val uiState: StateFlow<GlobalSearchUiState> = _uiState.asStateFlow()

    private var chatsById: Map<String, Chat> = emptyMap()
    private var localSearchJob: Job? = null
    private var pendingAiQuery: String? = null
    private var generation = 0L

    init {
        refreshIndex()
        _uiState.update { it.copy(recentSearches = RecentSearches.load(getApplication())) }
    }

    private fun text(id: Int): String = getApplication<Application>().getString(id)

    /**
     * 搜索类型过滤 → 对应可索引消息类型。
     * LINK 无独立 MessageType，落在 TEXT/MARKDOWN 上，返回后在内存按 URL 判定。
     */
    private fun filterToMessageTypes(filterType: SearchFilterType): List<String>? {
        return when (filterType) {
            SearchFilterType.ALL -> null
            SearchFilterType.TEXT -> listOf("TEXT", "MARKDOWN")
            SearchFilterType.IMAGE -> listOf("IMAGE", "GIF", "STICKER")
            SearchFilterType.FILE -> listOf("FILE")
            SearchFilterType.VOICE -> listOf("VOICE")
            SearchFilterType.VIDEO -> listOf("VIDEO")
            SearchFilterType.LINK -> listOf("TEXT", "MARKDOWN")
        }
    }

    private fun isLinkDocument(document: MessageSearchDocumentEntity): Boolean =
        document.searchableText.contains("://") ||
            document.searchableText.contains(Regex("(?i)\\b(www\\.|t\\.me/|chat\\.mdou\\.me/)"))

    /** 按当前 filterType 过滤搜索结果；LINK 类型在内存按 URL 判定。 */
    private fun applyTypeFilter(
        documents: List<MessageSearchDocumentEntity>,
        filterType: SearchFilterType
    ): List<MessageSearchDocumentEntity> {
        if (filterType == SearchFilterType.LINK) return documents.filter(::isLinkDocument)
        return documents
    }

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

    fun retryLastAiSearch() {
        val query = _uiState.value.query.trim()
        if (query.isBlank() || _uiState.value.mode != GlobalSearchMode.AI) return
        requestAiSearch()
    }

    fun retryLastKeywordSearch() {
        val query = _uiState.value.query
        if (query.isBlank() || _uiState.value.mode != GlobalSearchMode.KEYWORD) return
        _uiState.update { it.copy(error = null) }
        scheduleLocalSearch(query)
    }

    fun requestAiSearch() {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.GLOBAL_SEARCH)) {
            _uiState.update { it.copy(error = text(R.string.global_search_disabled)) }
            return
        }
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.AI_MASTER)) {
            _uiState.update { it.copy(error = text(R.string.ai_semantic_search_disabled)) }
            return
        }
        val query = _uiState.value.query.trim()
        if (query.isBlank() || _uiState.value.isSearching || _uiState.value.isIndexing) return
        if (!com.maodouchat.ai.AiPrivacyPreferences.consentAccepted(getApplication())) {
            pendingAiQuery = query
            _uiState.update { it.copy(showAiConsent = true) }
            return
        }
        performAiSearch(query)
    }

    fun acceptAiConsent() {
        com.maodouchat.ai.AiPrivacyPreferences.setConsentAccepted(getApplication(), true)
        val query = pendingAiQuery
        pendingAiQuery = null
        _uiState.update { it.copy(showAiConsent = false) }
        if (!query.isNullOrBlank()) performAiSearch(query)
    }

    fun dismissAiConsent() {
        pendingAiQuery = null
        _uiState.update { it.copy(showAiConsent = false) }
    }

    private fun refreshIndex() {
        val indexOwnerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (
            indexOwnerUserId.isBlank() ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = indexOwnerUserId,
            )
        ) {
            _uiState.update {
                it.copy(isIndexing = false, results = emptyList(), error = text(R.string.error_session_expired))
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isIndexing = true, error = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = indexOwnerUserId,
                )
                ) {
                    _uiState.update {
                        it.copy(isIndexing = false, results = emptyList(), error = text(R.string.error_session_expired))
                    }
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    chatsById = chatRepository.getAllChats().firstOrNull().orEmpty().associateBy(Chat::id)
                    // 8.31 性能修复 F18：索引新鲜时跳过全量重建（依赖增量维护），
                    // 避免每次打开全局搜索都全表扫描卡顿。
                    searchRepository.refreshIndexIfStale()
                }
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = indexOwnerUserId,
                )
                ) {
                    _uiState.update {
                        it.copy(isIndexing = false, results = emptyList(), error = text(R.string.error_session_expired))
                    }
                    return@launch
                }
                _uiState.update { it.copy(isIndexing = false) }
                if (_uiState.value.mode == GlobalSearchMode.KEYWORD) scheduleLocalSearch(_uiState.value.query)
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isIndexing = false) }
                throw error
            } catch (_: Exception) {
                _uiState.update { it.copy(isIndexing = false, error = text(R.string.global_search_index_failed)) }
            }
        }
    }

    private fun scheduleLocalSearch(query: String) {
        localSearchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(results = emptyList(), error = null) }
            return
        }
        // Indexing in progress is not "no hits" — keep the spinner, reschedule from refreshIndex.
        if (_uiState.value.isIndexing) return
        val expectedGeneration = generation
        val searchOwnerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (
            searchOwnerUserId.isBlank() ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = searchOwnerUserId,
            )
        ) {
            _uiState.update { it.copy(results = emptyList(), error = text(R.string.error_session_expired)) }
            return
        }
        localSearchJob = viewModelScope.launch {
            try {
                delay(180)
                if (
                    expectedGeneration != generation || _uiState.value.mode != GlobalSearchMode.KEYWORD ||
                    !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = searchOwnerUserId,
                    )
                ) {
                    return@launch
                }
                recordRecentSearch(query)
                val (documents, redactedChatCount) = withContext(Dispatchers.IO) {
                    // U02 延伸：PIN 锁 ∪ 密聊的隐藏集合收进非 ui 的 ChatVisibilitySets。
                    val redacted = com.maodouchat.data.repository.ChatVisibilitySets.redactedChatIds()
                    val filterType = _uiState.value.filterType
                    val all = filterToMessageTypes(filterType)?.let { types ->
                        searchRepository.searchByTypes(query, types, limit = 80)
                    } ?: searchRepository.search(query, limit = 80)
                    val visible = all
                        .filterNot { it.chatId in redacted }
                        .let { applyTypeFilter(it, filterType) }
                    val redactedHitChats = all.map { it.chatId }.distinct().count { it in redacted }
                    visible to redactedHitChats
                }
                if (
                    expectedGeneration != generation || _uiState.value.mode != GlobalSearchMode.KEYWORD ||
                    !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = searchOwnerUserId,
                    )
                ) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        results = documents.map(::toHit),
                        excludedChatCount = redactedChatCount,
                        error = null
                    )
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (expectedGeneration != generation || _uiState.value.mode != GlobalSearchMode.KEYWORD) {
                    return@launch
                }
                android.util.Log.w("GlobalSearchViewModel", "keyword search failed", error)
                _uiState.update {
                    it.copy(
                        results = emptyList(),
                        error = text(R.string.contacts_search_failed)
                    )
                }
            }
        }
    }

    private fun performAiSearch(query: String) {
        val requestGeneration = ++generation
        viewModelScope.launch {
            recordRecentSearch(query)
            _uiState.update {
                it.copy(isSearching = true, results = emptyList(), aiSearchCompleted = false, excludedChatCount = 0, error = null)
            }
            val searchOwnerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
            if (!com.maodouchat.session.CurrentSession.hasSession() || searchOwnerUserId.isBlank()) {
                _uiState.update { it.copy(isSearching = false, error = text(R.string.error_session_expired)) }
                return@launch
            }
            try {
                val localCandidates = withContext(Dispatchers.IO) {
                    // U02 延伸：隐藏集合收进非 ui 的 ChatVisibilitySets（同文本搜索路径）。
                    val redacted = com.maodouchat.data.repository.ChatVisibilitySets.redactedChatIds()
                    val filterType = _uiState.value.filterType
                    val docs = filterToMessageTypes(filterType)?.let { types ->
                        searchRepository.searchByTypes(query, types, limit = 80)
                    } ?: searchRepository.search(query, limit = 80)
                    docs
                        .filterNot { it.chatId in redacted }
                        .let { applyTypeFilter(it, filterType) }
                }
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = searchOwnerUserId,
                )
                ) {
                    _uiState.update {
                        it.copy(isSearching = false, error = text(R.string.error_session_expired))
                    }
                    return@launch
                }
                val selectedChatIds = localCandidates.map(MessageSearchDocumentEntity::chatId).distinct().take(32)
                val allowedDocuments = localCandidates.take(80)
                val outcome = if (allowedDocuments.isEmpty()) {
                    AiSearchOutcome(emptyList(), 0, noContext = true)
                } else {
                    val ranked = com.maodouchat.ai.agent.LocalAiGateway.rankSemanticScored(
                        query,
                        allowedDocuments.map { "${it.chatId}\t${it.messageId}" to it.searchableText }
                    )
                    val documentsByKey = allowedDocuments.associateBy { "${it.chatId}\t${it.messageId}" }
                    val hits = ranked
                        .mapNotNull { (key, score) -> documentsByKey[key]?.let { toHit(it, score) } }
                        .distinctBy { it.chatId to it.messageId }
                        .take(32)
                    AiSearchOutcome(hits, selectedChatIds.size - allowedDocuments.map { it.chatId }.toSet().size, noContext = false)
                }
                if (requestGeneration != generation) return@launch
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        results = outcome.hits,
                        aiSearchCompleted = true,
                        excludedChatCount = outcome.excludedChats,
                        error = if (outcome.noContext) text(R.string.global_search_ai_no_context) else null
                    )
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (requestGeneration == generation) {
                    _uiState.update { it.copy(isSearching = false) }
                }
                throw error
            } catch (error: Exception) {
                if (requestGeneration != generation) return@launch
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        aiSearchCompleted = true,
                        error = error.message ?: text(R.string.global_search_ai_failed)
                    )
                }
            }
        }
    }

    private fun toHit(document: MessageSearchDocumentEntity, score: Double? = null): GlobalSearchHit {
        return GlobalSearchHit(
            chatId = document.chatId,
            messageId = document.messageId,
            chatName = chatName(document.chatId),
            senderName = senderName(document),
            text = document.searchableText.replace('\n', ' ').trim(),
            timestamp = document.timestamp,
            semanticScore = score,
            messageType = document.messageType
        )
    }

    private fun chatName(chatId: String): String {
        val chat = chatsById[chatId] ?: return text(R.string.chat_unknown)
        return if (chat.isGroup) chat.groupName?.takeIf(String::isNotBlank) ?: text(R.string.chat_group)
        else chat.participants.firstOrNull()?.displayName?.takeIf(String::isNotBlank)
            ?: text(R.string.chat_unknown)
    }

    private fun senderName(document: MessageSearchDocumentEntity): String {
        if (document.senderId == com.maodouchat.session.CurrentSession.snapshot().userId) return text(R.string.chat_me)
        return chatsById[document.chatId]?.participants
            ?.firstOrNull { it.id == document.senderId }
            ?.displayName
            ?: document.senderId
    }

    private data class AiSearchOutcome(
        val hits: List<GlobalSearchHit>,
        val excludedChats: Int,
        val noContext: Boolean
    )

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

/** 最近搜索历史：本机存储，上限 8 条，去重、最新在前。 */
internal object RecentSearches {
    private const val PREFS = "global_search_recent"
    private const val KEY = "queries"
    private const val MAX = 8

    fun load(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) add(arr.getString(i))
            }.filter(String::isNotBlank)
        }.getOrDefault(emptyList())
    }

    fun push(context: Context, query: String): List<String> {
        val existing = load(context).filterNot { it.equals(query, ignoreCase = true) }
        val updated = (listOf(query) + existing).take(MAX)
        persist(context, updated)
        return updated
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(KEY) }
    }

    private fun persist(context: Context, queries: List<String>) {
        val arr = org.json.JSONArray()
        queries.forEach(arr::put)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY, arr.toString()) }
    }
}
