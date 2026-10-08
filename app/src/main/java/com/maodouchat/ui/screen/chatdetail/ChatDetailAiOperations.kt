package com.maodouchat.ui.screen.chatdetail

import android.app.Application
import com.maodouchat.R
import com.maodouchat.ai.AiPrivacyPreferences
import com.maodouchat.ai.AiRetryPolicy
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun ChatDetailViewModel.loadAiSettings() {
    val app = getApplication<Application>()
    val ownerUserId = currentUserId
    if (token.isBlank() || ownerUserId.isBlank()) {
        aiSettingsLoaded = true
        _uiState.update { it.copy(aiEnabled = false) }
        return
    }
    viewModelScope.launch {
        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = ownerUserId,
        )
        ) {
            aiSettingsLoaded = true
            return@launch
        }
        aiSettingsLoaded = true
        val enabled = com.maodouchat.ai.AiPrivacyPreferences.userEnabled(app) &&
            com.maodouchat.ai.AiPrivacyPreferences.consentAccepted(app) &&
            com.maodouchat.util.RuntimeFlags.isEnabled(app, com.maodouchat.util.RuntimeFlags.AI_MASTER)
        _uiState.update { it.copy(aiEnabled = enabled) }
        if (enabled) maybeGenerateUnreadSummary(_uiState.value.messages)
    }
}

internal fun ChatDetailViewModel.runAiWithConsent(action: PendingAiAction) {
    val app = getApplication<Application>()
    if (com.maodouchat.ai.AiPrivacyPreferences.consentAccepted(app)) {
        executeAiAction(action)
    } else {
        pendingAiAction = action
        pendingAiOperationRetryId = null
        _uiState.update { it.copy(showAiConsentDialog = true) }
    }
}

internal fun ChatDetailViewModel.executeAiAction(action: PendingAiAction) {
    val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
    if (ownerUserId.isBlank() || token.isBlank() || activeChatId.isBlank()) {
        _uiState.update {
            it.copy(groupEncryptionWarning = text(R.string.chat_ai_operation_context_missing))
        }
        return
    }
    val preparation = ChatAiOperationFactory.prepare(action, ownerUserId, activeChatId)
    if (preparation == AiOperationPreparation.InvalidContext) {
        _uiState.update {
            it.copy(groupEncryptionWarning = text(R.string.chat_ai_operation_context_missing))
        }
        return
    }
    val category = action.invocationCategory()
    val wait = AiRetryPolicy.remainingDelayMs(activeChatId, category)
    if (wait > 0L) {
        val message = text(R.string.chat_ai_too_soon, (wait / 1000L).coerceAtLeast(1L))
        _uiState.update {
            if (action is PendingAiAction.SemanticSearch) it.copy(semanticSearchError = message)
            else it.copy(groupEncryptionWarning = message)
        }
        return
    }
    AiRetryPolicy.recordCall(activeChatId, category)
    when (preparation) {
        AiOperationPreparation.Untracked -> dispatchAiAction(action, null)
        is AiOperationPreparation.Persisted -> viewModelScope.launch(Dispatchers.IO) {
            aiOperationRepo.enqueue(preparation.operation)
            pumpAiOperationQueue()
        }
        AiOperationPreparation.InvalidContext -> Unit
    }
}

internal fun ChatDetailViewModel.dispatchAiAction(action: PendingAiAction, operationId: String?) {
    when (action) {
        is PendingAiAction.Rewrite -> rewriteDraft(action.mode, action.targetLanguage)
        is PendingAiAction.SuggestReplies -> generateAiSuggestions(action.tone)
        is PendingAiAction.Summarize -> summarizeMessages(action.scope, action.messages, operationId, action.style)
        is PendingAiAction.TranscribeVoice -> transcribeVoiceMessage(action.messageId, operationId)
        is PendingAiAction.TranslateMessage -> translateTextMessage(action.messageId, action.targetLanguage, operationId)
        is PendingAiAction.AnalyzeImage -> analyzeImageMessage(action.messageId, action.mode, operationId)
        is PendingAiAction.AnalyzeFile -> analyzeFileMessage(action.messageId, action.mode, action.question, operationId)
        is PendingAiAction.SemanticSearch -> semanticSearch(action.query, action.candidateMessageIds)
        is PendingAiAction.GroupAssistant -> groupAssistant(action.query, action.mode)
    }
}
