package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.util.RuntimeFlags
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.ai.AiPrivacyPreferences
import com.maodouchat.data.local.entity.AiOperationState
import com.maodouchat.data.local.entity.AiTaskEntity
import com.maodouchat.data.model.MessageMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID


fun ChatDetailViewModel.requestSemanticSearch(query: String, candidateMessageIds: List<String>) {
        when (
            val admission = searchSelectionStateController.admitSemanticSearch(
                state = _uiState.value,
                query = query,
                candidateMessageIds = candidateMessageIds,
                secretChatError = text(R.string.secret_chat_ai_blocked),
                aiDisabledError = text(R.string.chat_ai_disabled_warning),
                blankQueryError = text(R.string.chat_semantic_search_enter_query),
                noContextError = text(R.string.chat_semantic_search_no_context),
            )
        ) {
            is ChatSearchSelectionStateController.SemanticSearchAdmission.Accepted -> {
                runAiWithConsent(PendingAiAction.SemanticSearch(admission.query, admission.candidateIds))
            }
            is ChatSearchSelectionStateController.SemanticSearchAdmission.Rejected -> {
                _uiState.update { admission.state }
            }
        }
}

fun ChatDetailViewModel.clearSemanticSearch() {
        semanticSearchGate.invalidate()
        semanticSearchJob?.cancel()
        semanticSearchJob = null
        if (pendingAiAction is PendingAiAction.SemanticSearch) {
            pendingAiAction = null
        }
        _uiState.update { state ->
            searchSelectionStateController.clearSemanticSearch(
                state,
                showConsentDialog = pendingAiAction != null && state.showAiConsentDialog,
            )
        }
}

fun ChatDetailViewModel.consumeNavigationTarget() {
        _uiState.update(searchSelectionStateController::consumeNavigation)
}

fun ChatDetailViewModel.acceptAiConsentAndContinue() {
        com.maodouchat.ai.AiPrivacyPreferences.setConsentAccepted(app, true)
        val action = pendingAiAction
        val retryOperationId = pendingAiOperationRetryId
        pendingAiAction = null
        pendingAiOperationRetryId = null
        _uiState.update { it.copy(showAiConsentDialog = false) }
        when {
            retryOperationId != null -> retryAiOperation(retryOperationId)
            action != null -> executeAiAction(action)
        }
        maybeGenerateUnreadSummary(_uiState.value.messages)
}

fun ChatDetailViewModel.dismissAiConsent() {
        pendingAiAction = null
        pendingAiOperationRetryId = null
        _uiState.update { it.copy(showAiConsentDialog = false) }
}

fun ChatDetailViewModel.applyAiSuggestion(text: String) {
        cancelAiReplyStream(clearSuggestions = true)
        hasUserEditedInput = true
        _uiState.update { it.copy(inputText = text, aiSuggestions = emptyList()) }
        scheduleDraftPersistence(text)
}

fun ChatDetailViewModel.clearAiSuggestions() {
        cancelAiReplyStream(clearSuggestions = true)
}

fun ChatDetailViewModel.clearAiSummary() {
        _uiState.update { it.copy(aiSummary = null, aiSummaryScope = null, aiSummaryMessageCount = 0) }
}

fun ChatDetailViewModel.openAiSummaryHistory() {
        val request = captureAiRequestSnapshot()
        if (request == null) {
            _uiState.update { it.copy(showAiSummaryHistory = false, isAiSummaryHistoryLoading = false) }
            return
        }
        _uiState.update { it.copy(showAiSummaryHistory = true, isAiSummaryHistoryLoading = true) }
        viewModelScope.launch {
            try {
                val summaries = withContext(Dispatchers.IO) {
                    aiSummaryRepo.getSummariesForChat(request.chatId, limit = 30)
                }
                requireAiRequestCurrent(request)
                _uiState.update {
                    it.copy(
                        isAiSummaryHistoryLoading = false,
                        aiSummaryHistory = summaries.map { entity ->
                            AiSummaryHistoryUi(
                                cacheKey = entity.cacheKey,
                                summary = entity.summary,
                                scope = summaryScopeFromCacheKey(entity.cacheKey),
                                messageCount = entity.messageCount,
                                createdAt = entity.createdAt
                            )
                        }
                    )
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isAiRequestCurrent(request)) {
                    _uiState.update { it.copy(isAiSummaryHistoryLoading = false) }
                }
                throw error
            } catch (_: Exception) {
                if (isAiRequestCurrent(request)) {
                    _uiState.update { state -> state.copy(isAiSummaryHistoryLoading = false) }
                }
            }
        }
}

fun ChatDetailViewModel.dismissAiSummaryHistory() {
        _uiState.update { it.copy(showAiSummaryHistory = false) }
}

fun ChatDetailViewModel.openAiSummaryFromHistory(cacheKey: String) {
        val item = _uiState.value.aiSummaryHistory.firstOrNull { it.cacheKey == cacheKey } ?: return
        _uiState.update {
            it.copy(
                showAiSummaryHistory = false,
                aiSummary = item.summary,
                aiSummaryScope = item.scope,
                aiSummaryMessageCount = item.messageCount
            )
        }
}

internal fun ChatDetailViewModel.summaryScopeFromCacheKey(cacheKey: String): AiSummaryScope {
        if (!cacheKey.startsWith("manual:")) return AiSummaryScope.UNREAD
        return cacheKey.split(':').getOrNull(2)
            ?.let { value -> AiSummaryScope.entries.firstOrNull { it.name == value } }
            ?: AiSummaryScope.RECENT
}

fun ChatDetailViewModel.clearGroupAiAnswer() {
        _uiState.update {
            it.copy(
                groupAiAnswer = null,
                groupAiQuestion = "",
                groupAiMode = "answer",
                groupAiTasks = emptyList(),
                isSavingGroupAiTasks = false,
                groupAiTasksSaved = false,
                groupAiTaskSaveError = null,
                groupAiAnswerShared = false
            )
        }
}

fun ChatDetailViewModel.saveGroupAiTasks() {
        val state = _uiState.value
        val targetChatId = activeChatId.takeIf(String::isNotBlank) ?: return
        if (state.isSavingGroupAiTasks || state.groupAiTasksSaved) return
        val drafts = com.maodouchat.ai.GroupAiSharePolicy.sanitizeTasks(
            state.groupAiTasks.map {
                com.maodouchat.ai.GroupAiSharePolicy.TaskDraft(
                    title = it.title,
                    owner = it.owner,
                    dueText = it.dueText,
                    dueAt = it.dueAt
                )
            }
        )
        if (drafts.isEmpty()) return

        val sourceQuery = state.groupAiQuestion.trim().take(600)
        val now = System.currentTimeMillis()
        val entities = drafts.map { task ->
            AiTaskEntity(
                id = "ai_task_${UUID.randomUUID()}",
                chatId = targetChatId,
                sourceQuery = sourceQuery,
                title = task.title,
                owner = task.owner,
                dueText = task.dueText,
                dueAt = task.dueAt,
                createdAt = now,
                updatedAt = now
            )
        }

        _uiState.update {
            it.copy(isSavingGroupAiTasks = true, groupAiTaskSaveError = null)
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { aiTaskRepo.saveTasks(entities) }
                _uiState.update {
                    it.copy(
                        isSavingGroupAiTasks = false,
                        groupAiTasksSaved = true,
                        groupAiTaskSaveError = null
                    )
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isSavingGroupAiTasks = false) }
                throw error
            } catch (_: Throwable) {
                _uiState.update {
                    it.copy(
                        isSavingGroupAiTasks = false,
                        groupAiTaskSaveError = text(R.string.ai_tasks_save_failed)
                    )
                }
            }
        }
}

fun ChatDetailViewModel.shareGroupAiAnswer() {
        val state = _uiState.value
        val decision = com.maodouchat.ai.GroupAiSharePolicy.decideShare(
            isGroup = state.chat?.isGroup == true,
            answer = state.groupAiAnswer,
            alreadyShared = state.groupAiAnswerShared
        )
        if (!decision.allowed) return
        // Fail-closed: mark shared before send so double-tap cannot enqueue twice.
        _uiState.update { it.copy(groupAiAnswerShared = true) }
        val meta = com.maodouchat.data.model.MessageMeta(
            aiAssisted = com.maodouchat.ai.GroupAiSharePolicy.shareAiAssistedFlag(),
            aiAssistantMode = com.maodouchat.ai.GroupAiSharePolicy.shareAssistantMode(state.groupAiMode)
        )
        // Always send as the current user with AI-assisted meta — never a system identity.
        sendMessage(
            forceText = decision.body,
            silent = meta.silent,
            forcedMeta = meta.copy(markdown = meta.markdown),
        )
        clearGroupAiAnswer()
}

fun ChatDetailViewModel.clearUnreadAiSummary() {
        _uiState.update { it.copy(unreadAiSummary = null, unreadAiSummaryCount = 0) }
}

fun ChatDetailViewModel.openUnreadAiSummary() {
        val summary = _uiState.value.unreadAiSummary ?: return
        _uiState.update {
            it.copy(
                aiSummary = summary,
                aiSummaryScope = AiSummaryScope.UNREAD,
                aiSummaryMessageCount = it.unreadAiSummaryCount
            )
        }
}

fun ChatDetailViewModel.setAiEnabledForChat(enabled: Boolean) {
        val ownerUserId = currentUserId
        if (token.isBlank() || ownerUserId.isBlank()) {
            _uiState.update {
                it.copy(
                    isUpdatingAiSetting = false,
                    groupEncryptionWarning = text(R.string.error_session_expired)
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isUpdatingAiSetting = true, groupEncryptionWarning = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                )
                ) {
                    _uiState.update {
                        it.copy(isUpdatingAiSetting = false, groupEncryptionWarning = text(R.string.error_session_expired))
                    }
                    return@launch
                }
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                )
                ) {
                    _uiState.update {
                        it.copy(isUpdatingAiSetting = false, groupEncryptionWarning = text(R.string.error_session_expired))
                    }
                    return@launch
                }
                AiPrivacyPreferences.setUserEnabled(app, enabled)
                val effective = enabled &&
                    AiPrivacyPreferences.consentAccepted(app) &&
                    RuntimeFlags.isEnabled(app, RuntimeFlags.AI_MASTER)
                _uiState.update {
                    it.copy(
                        aiEnabled = effective,
                        aiSuggestions = if (effective) it.aiSuggestions else emptyList(),
                        isUpdatingAiSetting = false,
                        groupEncryptionWarning = if (effective) {
                            text(R.string.chat_ai_enabled_status)
                        } else {
                            text(R.string.chat_ai_disabled_status)
                        }
                    )
                }
                if (effective) {
                    maybeGenerateUnreadSummary(_uiState.value.messages)
                } else {
                    discardAiDraftPreview()
                    cancelAiReplyStream(clearSuggestions = true)
                    _uiState.value.aiOperations
                        .filter { it.state in setOf(AiOperationState.QUEUED, AiOperationState.RUNNING) }
                        .forEach { cancelAiOperation(it.id) }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isUpdatingAiSetting = false) }
                throw error
            }
        }
}
