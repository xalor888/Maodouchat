package com.maodouchat.ui.screen.chatlist

import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.data.local.entity.MessageSearchDocumentEntity
import com.maodouchat.data.model.Chat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 搜索类型过滤 → 对应可索引消息类型。
 * LINK 无独立 MessageType，落在 TEXT/MARKDOWN 上，返回后在内存按 URL 判定。
 */
fun GlobalSearchViewModel.filterToMessageTypes(filterType: SearchFilterType): List<String>? {
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

fun GlobalSearchViewModel.isLinkDocument(document: MessageSearchDocumentEntity): Boolean =
    document.searchableText.contains("://") ||
        document.searchableText.contains(Regex("(?i)\\b(www\\.|t\\.me/|chat\\.mdou\\.me/)"))

/** 按当前 filterType 过滤搜索结果；LINK 类型在内存按 URL 判定。 */
fun GlobalSearchViewModel.applyTypeFilter(
    documents: List<MessageSearchDocumentEntity>,
    filterType: SearchFilterType
): List<MessageSearchDocumentEntity> {
    if (filterType == SearchFilterType.LINK) return documents.filter(::isLinkDocument)
    return documents
}

fun GlobalSearchViewModel.retryLastKeywordSearch() {
    val query = _uiState.value.query
    if (query.isBlank() || _uiState.value.mode != GlobalSearchMode.KEYWORD) return
    _uiState.update { it.copy(error = null) }
    scheduleLocalSearch(query)
}

fun GlobalSearchViewModel.refreshIndex() {
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

fun GlobalSearchViewModel.scheduleLocalSearch(query: String) {
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

fun GlobalSearchViewModel.toHit(document: MessageSearchDocumentEntity, score: Double? = null): GlobalSearchHit {
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

fun GlobalSearchViewModel.chatName(chatId: String): String {
    val chat = chatsById[chatId] ?: return text(R.string.chat_unknown)
    return if (chat.isGroup) chat.groupName?.takeIf(String::isNotBlank) ?: text(R.string.chat_group)
    else chat.participants.firstOrNull()?.displayName?.takeIf(String::isNotBlank)
        ?: text(R.string.chat_unknown)
}

fun GlobalSearchViewModel.senderName(document: MessageSearchDocumentEntity): String {
    if (document.senderId == com.maodouchat.session.CurrentSession.snapshot().userId) return text(R.string.chat_me)
    return chatsById[document.chatId]?.participants
        ?.firstOrNull { it.id == document.senderId }
        ?.displayName
        ?: document.senderId
}
