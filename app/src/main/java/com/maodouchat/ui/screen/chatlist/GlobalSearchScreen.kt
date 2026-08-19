package com.maodouchat.ui.screen.chatlist

import com.maodouchat.util.RuntimeFlags
import android.app.Application
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.data.local.entity.MessageSearchDocumentEntity
import com.maodouchat.data.model.Chat
import com.maodouchat.data.repository.ChatRepository
import com.maodouchat.data.repository.MessageSearchRepository
import com.maodouchat.network.ApiService
import com.maodouchat.network.AiGlobalSemanticSearchCandidate
import com.maodouchat.network.TokenManager
import com.maodouchat.ui.theme.Background
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Outline
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.Surface
import com.maodouchat.ui.theme.TextHint
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.ui.theme.UnreadRed
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.MotionTokens
import com.maodouchat.ui.theme.MotionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

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
    private val app = application as MaodouchatApp
    private val searchRepository = MessageSearchRepository(app.database)
    private val chatRepository = ChatRepository(app.database.chatDao(), app.database.userDao())
    private val tokenManager = TokenManager.getInstance(application)
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
                // Typing never auto-fires AI; cancel in-flight AI display state.
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

    fun requestAiSearch() {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.GLOBAL_SEARCH)) {
            _uiState.update { it.copy(error = text(R.string.global_search_disabled)) }
            return
        }
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.AI_SEMANTIC_SEARCH)) {
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
        val indexOwnerUserId = tokenManager.getUserId().orEmpty()
        if (
            indexOwnerUserId.isBlank() ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = indexOwnerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
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
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
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
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
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
        if (query.isBlank() || _uiState.value.isIndexing) {
            _uiState.update { it.copy(results = emptyList()) }
            return
        }
        val expectedGeneration = generation
        val searchOwnerUserId = tokenManager.getUserId().orEmpty()
        if (
            searchOwnerUserId.isBlank() ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = searchOwnerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            _uiState.update { it.copy(results = emptyList(), error = text(R.string.error_session_expired)) }
            return
        }
        localSearchJob = viewModelScope.launch {
            delay(180)
            if (
                expectedGeneration != generation ||
                _uiState.value.mode != GlobalSearchMode.KEYWORD ||
                !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = searchOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@launch
            }
            recordRecentSearch(query)
            val (documents, redactedChatCount) = withContext(Dispatchers.IO) {
                val locked = app.database.chatLockDao().listLockedChatIds().toSet()
                val secret = app.database.secretChatDao().listSecretChatIds().toSet()
                val redacted = locked + secret
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
                expectedGeneration != generation ||
                _uiState.value.mode != GlobalSearchMode.KEYWORD ||
                !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = searchOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
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
        }
    }

    private fun performAiSearch(query: String) {
        val requestGeneration = ++generation
        viewModelScope.launch {
            recordRecentSearch(query)
            _uiState.update {
                it.copy(isSearching = true, results = emptyList(), aiSearchCompleted = false, excludedChatCount = 0, error = null)
            }
            val token = tokenManager.getToken().orEmpty()
            val searchOwnerUserId = tokenManager.getUserId().orEmpty()
            if (token.isBlank() || searchOwnerUserId.isBlank()) {
                _uiState.update { it.copy(isSearching = false, error = text(R.string.error_session_expired)) }
                return@launch
            }
            try {
                val localCandidates = withContext(Dispatchers.IO) {
                    val locked = app.database.chatLockDao().listLockedChatIds().toSet()
                    val secret = app.database.secretChatDao().listSecretChatIds().toSet()
                    val filterType = _uiState.value.filterType
                    val docs = filterToMessageTypes(filterType)?.let { types ->
                        searchRepository.searchByTypes(query, types, limit = 80)
                    } ?: searchRepository.search(query, limit = 80)
                    docs
                        .filterNot { it.chatId in locked || it.chatId in secret }
                        .let { applyTypeFilter(it, filterType) }
                }
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = searchOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@launch
                }
                val settingsToken = tokenManager.getToken().orEmpty().ifBlank { token }
                val selectedChatIds = localCandidates.map(MessageSearchDocumentEntity::chatId).distinct().take(32)
                val settings = coroutineScope {
                    selectedChatIds.map { chatId ->
                        async { chatId to ApiService.getAiSettings(settingsToken, chatId).getOrNull()?.effectiveEnabled }
                    }.awaitAll()
                }
                val enabledChatIds = settings.filter { it.second == true }.mapTo(hashSetOf()) { it.first }
                val excluded = selectedChatIds.size - enabledChatIds.size
                val allowedDocuments = localCandidates
                    .filter { it.chatId in enabledChatIds && SERVER_MESSAGE_ID.matches(it.messageId) }
                    .take(80)
                val outcome = if (allowedDocuments.isEmpty()) {
                    AiSearchOutcome(emptyList(), excluded, noContext = true)
                } else {
                    val candidates = allowedDocuments.map { document ->
                        AiGlobalSemanticSearchCandidate(
                            chatId = document.chatId,
                            messageId = document.messageId,
                            sender = "${chatName(document.chatId)} / ${senderName(document)}".take(80),
                            text = document.searchableText.take(1000),
                            timestamp = document.timestamp
                        )
                    }
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = searchOwnerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        return@launch
                    }
                    val liveToken = tokenManager.getToken() ?: token
                    val response = ApiService.globalSemanticSearch(liveToken, query, candidates, limit = 32).getOrThrow()
                    val documentsByKey = allowedDocuments.associateBy { it.chatId to it.messageId }
                    val allowedKeys = documentsByKey.keys
                    val hits = response.matches.asSequence()
                        .filter { (it.chatId to it.messageId) in allowedKeys && it.score > 0.0 }
                        .distinctBy { it.chatId to it.messageId }
                        .take(32)
                        .mapNotNull { match ->
                            documentsByKey[match.chatId to match.messageId]?.let { toHit(it, match.score) }
                        }
                        .toList()
                    AiSearchOutcome(hits, excluded, noContext = false)
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
        if (document.senderId == tokenManager.getUserId()) return text(R.string.chat_me)
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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    private fun persist(context: Context, queries: List<String>) {
        val arr = org.json.JSONArray()
        queries.forEach(arr::put)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSearchScreen(
    onBack: () -> Unit,
    onOpenResult: (chatId: String, messageId: String) -> Unit,
    viewModel: GlobalSearchViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val motion = LocalMotionSettings.current

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.global_search_title), color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back), tint = Primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    placeholder = { Text(stringResource(R.string.global_search_placeholder), color = TextHint) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = Outline) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onQueryChange("") }) {
                                Icon(Icons.Outlined.Close, stringResource(R.string.global_search_clear), tint = TextSecondary)
                            }
                        }
                    },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GlobalSearchMode.entries.forEach { mode ->
                        val selected = state.mode == mode
                        val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        val pressed by interactionSource.collectIsPressedAsState()
                        val pressScale by androidx.compose.animation.core.animateFloatAsState(
                            targetValue = if (pressed) 0.95f else 1f,
                            animationSpec = androidx.compose.animation.core.spring(
                                dampingRatio = 0.55f, stiffness = 460f
                            ),
                            label = "globalSearchChipScale"
                        )
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.setMode(mode) },
                            label = {
                                Text(stringResource(if (mode == GlobalSearchMode.KEYWORD) R.string.global_search_mode_keyword else R.string.global_search_mode_ai))
                            },
                            modifier = Modifier.weight(1f).graphicsLayer { scaleX = pressScale; scaleY = pressScale },
                            interactionSource = interactionSource
                        )
                    }
                }
                if (state.mode == GlobalSearchMode.AI) {
                    Button(
                        onClick = viewModel::requestAiSearch,
                        enabled = state.query.isNotBlank() && !state.isIndexing && !state.isSearching,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.isSearching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(7.dp))
                            Text(stringResource(R.string.global_search_ai_action))
                        }
                    }
                    Text(
                        stringResource(R.string.global_search_ai_privacy),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextHint
                    )
                } else {
                    // 关键词搜索模式：添加消息类型筛选
                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        SearchFilterType.entries.forEach { filter ->
                            val selected = state.filterType == filter
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.setFilterType(filter) },
                                label = {
                                    Text(
                                        stringResource(filter.labelRes),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                modifier = Modifier.height(28.dp)
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(state.isIndexing, enter = fadeIn(), exit = fadeOut()) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Primary)
            }
            state.error?.let { errorText ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(UnreadRed.copy(alpha = 0.08f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        errorText,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = UnreadRed
                    )
                    if (state.mode == GlobalSearchMode.AI && state.query.isNotBlank() && !state.isSearching) {
                        TextButton(onClick = viewModel::retryLastAiSearch) {
                            Text(stringResource(R.string.global_search_ai_retry), color = Primary)
                        }
                    }
                }
            }
            if (state.excludedChatCount > 0) {
                Text(
                    stringResource(
                        if (state.mode == GlobalSearchMode.AI) {
                            R.string.global_search_excluded_chats
                        } else {
                            R.string.global_search_redacted_chats
                        },
                        state.excludedChatCount
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            when {
                state.query.isBlank() -> {
                    if (state.recentSearches.isNotEmpty()) {
                        RecentSearchesSection(
                            queries = state.recentSearches,
                            onPick = viewModel::useRecentSearch,
                            onClearAll = viewModel::clearRecentSearches
                        )
                    } else {
                        GlobalSearchEmpty(stringResource(R.string.global_search_empty_initial))
                    }
                }
                state.isIndexing || state.isSearching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Primary)
                }
                state.results.isEmpty() -> GlobalSearchEmpty(
                    stringResource(
                        if (state.mode == GlobalSearchMode.AI && !state.aiSearchCompleted) R.string.global_search_ai_ready
                        else R.string.global_search_empty_results
                    )
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 20.dp)
                ) {
                    itemsIndexed(
                        state.results,
                        key = { _, hit -> "${hit.chatId}:${hit.messageId}" },
                        contentType = { _, _ -> "search_hit" }
                    ) { index, hit ->
                        val animateInitialEntry = MotionPolicy.shouldAnimateInitialListEntry(index, motion)
                        var visible by remember(hit.chatId, hit.messageId, animateInitialEntry) {
                            mutableStateOf(!animateInitialEntry)
                        }
                        LaunchedEffect(hit.chatId, hit.messageId, animateInitialEntry) {
                            if (animateInitialEntry) kotlinx.coroutines.delay(
                                MotionPolicy.initialListEntryDelay(index, motion).toLong()
                            )
                            visible = true
                        }
                        androidx.compose.animation.AnimatedVisibility(
                            visible = visible,
                            enter = if (animateInitialEntry) {
                                androidx.compose.animation.expandVertically(tween(motion.duration(MotionTokens.Emphasized))) +
                                    fadeIn(tween(motion.duration(MotionTokens.Emphasized)))
                            } else {
                                EnterTransition.None
                            },
                        ) {
                            Column {
                                GlobalSearchResultRow(
                                    hit = hit,
                                    query = state.query,
                                    onClick = { onOpenResult(hit.chatId, hit.messageId) }
                                )
                                HorizontalDivider(color = Outline.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.showAiConsent) {
        AlertDialog(
            onDismissRequest = viewModel::dismissAiConsent,
            title = { Text(stringResource(R.string.chat_ai_consent_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.global_search_ai_consent_data), color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.chat_ai_consent_privacy), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::acceptAiConsent) { Text(stringResource(R.string.chat_ai_accept), color = Primary) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissAiConsent) { Text(stringResource(R.string.chat_later)) }
            }
        )
    }
}

@Composable
private fun GlobalSearchResultRow(hit: GlobalSearchHit, query: String, onClick: () -> Unit) {
    val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(hit.timestamp))
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            when (hit.messageType) {
                "IMAGE", "GIF", "STICKER" -> Icons.Outlined.Image
                "VIDEO" -> Icons.Outlined.Videocam
                "FILE" -> Icons.Outlined.Description
                "VOICE" -> Icons.Outlined.Mic
                "LOCATION" -> Icons.Outlined.LocationOn
                else -> if (hit.semanticScore != null) Icons.Outlined.AutoAwesome else Icons.Outlined.Search
            },
            contentDescription = null,
            tint = if (hit.semanticScore != null) Primary else TextHint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    hit.chatName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(time, style = MaterialTheme.typography.labelSmall, color = TextHint)
            }
            Text(hit.senderName, style = MaterialTheme.typography.labelMedium, color = Primary)
            Text(
                highlightedText(hit.text, query),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun highlightedText(text: String, query: String) = buildAnnotatedString {
    val snippet = remember(text, query) {
        GlobalSearchTextHighlight.buildSnippet(text, query)
    }
    if (snippet.highlights.isEmpty()) {
        append(snippet.text)
        return@buildAnnotatedString
    }
    var cursor = 0
    snippet.highlights.forEach { span ->
        if (span.start > cursor) append(snippet.text.substring(cursor, span.start))
        pushStyle(SpanStyle(color = Primary, fontWeight = FontWeight.SemiBold, background = Primary.copy(alpha = 0.12f)))
        append(snippet.text.substring(span.start, span.end))
        pop()
        cursor = span.end
    }
    if (cursor < snippet.text.length) append(snippet.text.substring(cursor))
}

@Composable
private fun GlobalSearchEmpty(message: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = TextHint, modifier = Modifier.size(42.dp))
            Spacer(modifier = Modifier.height(10.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = TextHint)
        }
    }
}

@Composable
private fun RecentSearchesSection(
    queries: List<String>,
    onPick: (String) -> Unit,
    onClearAll: () -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.global_search_recent_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onClearAll) {
                    Text(stringResource(R.string.global_search_recent_clear), color = TextSecondary)
                }
            }
        }
        items(queries, key = { "recent:$it" }) { query ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(query) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = null,
                    tint = TextHint,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    query,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            HorizontalDivider(color = Outline.copy(alpha = 0.5f))
        }
    }
}
