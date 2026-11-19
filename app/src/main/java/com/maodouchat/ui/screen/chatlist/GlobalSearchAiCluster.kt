package com.maodouchat.ui.screen.chatlist

import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.core.model.AiSearchOutcome
import com.maodouchat.data.local.entity.MessageSearchDocumentEntity
import com.maodouchat.domain.conversation.GlobalSearchMode
import com.maodouchat.domain.conversation.GlobalSearchQueryPolicy
import com.maodouchat.domain.conversation.GlobalSearchTypeFilter
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun GlobalSearchViewModel.retryLastAiSearch() {
    val query = _uiState.value.query.trim()
    if (query.isBlank() || _uiState.value.mode != GlobalSearchMode.AI) return
    requestAiSearch()
}

fun GlobalSearchViewModel.requestAiSearch() {
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

fun GlobalSearchViewModel.acceptAiConsent() {
    com.maodouchat.ai.AiPrivacyPreferences.setConsentAccepted(getApplication(), true)
    val query = pendingAiQuery
    pendingAiQuery = null
    _uiState.update { it.copy(showAiConsent = false) }
    if (!query.isNullOrBlank()) performAiSearch(query)
}

fun GlobalSearchViewModel.dismissAiConsent() {
    pendingAiQuery = null
    _uiState.update { it.copy(showAiConsent = false) }
}

fun GlobalSearchViewModel.performAiSearch(query: String) {
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
                val docs = GlobalSearchTypeFilter.messageTypesForFilter(filterType.apiValue)?.let { types ->
                    searchRepository.searchByTypes(query, types, limit = GlobalSearchQueryPolicy.AI_CANDIDATE_LIMIT)
                } ?: searchRepository.search(query, limit = GlobalSearchQueryPolicy.AI_CANDIDATE_LIMIT)
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
            val selectedChatIds = GlobalSearchQueryPolicy.limitChatIds(localCandidates.map(MessageSearchDocumentEntity::chatId))
            val allowedDocuments = GlobalSearchQueryPolicy.limitDocuments(localCandidates)
            val outcome = if (allowedDocuments.isEmpty()) {
                AiSearchOutcome(emptyList(), 0, noContext = true)
            } else {
                val ranked = com.maodouchat.ai.agent.LocalAiGateway.rankSemanticScored(
                    query,
                    allowedDocuments.map { "${it.chatId}\t${it.messageId}" to it.searchableText }
                )
                val documentsByKey = allowedDocuments.associateBy { "${it.chatId}\t${it.messageId}" }
                val hits = GlobalSearchQueryPolicy.limitHits(
                    ranked
                        .mapNotNull { (key, score) -> documentsByKey[key]?.let { toHit(it, score) } }
                        .distinctBy { it.chatId to it.messageId },
                )
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
