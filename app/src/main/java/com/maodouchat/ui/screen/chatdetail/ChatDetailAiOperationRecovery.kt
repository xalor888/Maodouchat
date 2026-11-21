package com.maodouchat.ui.screen.chatdetail

import android.app.Application
import com.maodouchat.R
import com.maodouchat.ai.AiPrivacyPreferences
import com.maodouchat.core.model.mergeMessageVersions
import com.maodouchat.data.local.entity.AiOperationEntity
import com.maodouchat.data.local.entity.AiOperationError
import com.maodouchat.core.model.Message
import com.maodouchat.domain.messaging.AiOperationParameters
import com.maodouchat.domain.messaging.AiOperationType
import com.maodouchat.domain.messaging.PendingAiAction
import com.maodouchat.domain.messaging.restoreAiAction
import com.maodouchat.domain.messaging.MessageLookupPolicy
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun ChatDetailViewModel.retryAiOperation(operationId: String) {
    val app = getApplication<Application>()
    if (aiOperationJobs.containsKey(operationId)) return
    aiAutoRetryJobs.remove(operationId)?.cancel()
    aiAutoRetryAt.remove(operationId)
    if (!_uiState.value.aiEnabled) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_disabled_warning)) }
        return
    }
    if (!com.maodouchat.ai.AiPrivacyPreferences.consentAccepted(app)) {
        pendingAiAction = null
        pendingAiOperationRetryId = operationId
        _uiState.update { it.copy(showAiConsentDialog = true) }
        return
    }
    viewModelScope.launch {
        val operation = withContext(Dispatchers.IO) { aiOperationRepo.get(operationId) } ?: return@launch
        if (operation.ownerUserId != currentUserId || operation.chatId != activeChatId) return@launch
        val action = restoreAiAction(operation)
        if (action == null) {
            withContext(Dispatchers.IO) {
                aiOperationRepo.markFailed(operationId, AiOperationError.CONTEXT_MISSING)
            }
            return@launch
        }
        val requeued = withContext(Dispatchers.IO) { aiOperationRepo.markQueued(operationId) }
        if (requeued) withContext(Dispatchers.IO) { pumpAiOperationQueue() }
    }
}

fun ChatDetailViewModel.cancelAiOperation(operationId: String) {
    aiAutoRetryJobs.remove(operationId)?.cancel()
    aiAutoRetryAt.remove(operationId)
    viewModelScope.launch {
        val operation = withContext(Dispatchers.IO) { aiOperationRepo.get(operationId) } ?: return@launch
        if (operation.ownerUserId != currentUserId || operation.chatId != activeChatId) return@launch
        val cancelled = withContext(Dispatchers.IO) { aiOperationRepo.markCancelled(operationId) }
        if (cancelled) {
            aiOperationJobs.remove(operationId)?.cancel()
            clearAiOperationUi(operation)
            withContext(Dispatchers.IO) { pumpAiOperationQueue() }
        }
    }
}

fun ChatDetailViewModel.dismissAiOperation(operationId: String) {
    aiAutoRetryJobs.remove(operationId)?.cancel()
    aiAutoRetryAt.remove(operationId)
    viewModelScope.launch(Dispatchers.IO) {
        val operation = aiOperationRepo.get(operationId) ?: return@launch
        if (operation.ownerUserId == currentUserId && operation.chatId == activeChatId) {
            aiOperationRepo.dismiss(operationId)
        }
    }
}

internal suspend fun ChatDetailViewModel.restoreAiAction(operation: AiOperationEntity): PendingAiAction? {
    val parameters = runCatching {
        aiOperationJson.decodeFromString(AiOperationParameters.serializer(), operation.parametersJson)
    }.getOrNull() ?: return null
    return restoreAiAction(operation.type, operation.targetMessageId, parameters, ::ensureAiOperationMessage)
}

internal suspend fun ChatDetailViewModel.ensureAiOperationMessage(messageId: String): Message? {
    MessageLookupPolicy.findById(_uiState.value.messages, messageId)?.let { return it }
    val cached = withContext(Dispatchers.IO) { messageRepo.getMessageById(messageId) }
        ?.takeIf { it.chatId == activeChatId }
        ?: return null
    _uiState.update { state -> state.copy(messages = mergeMessageVersions(state.messages, listOf(cached))) }
    return cached
}

internal fun ChatDetailViewModel.clearAiOperationUi(operation: AiOperationEntity) {
    _uiState.update { state ->
        when (operation.type) {
            AiOperationType.TRANSCRIBE_VOICE -> state.copy(
                transcribingVoiceMessageIds = operation.targetMessageId?.let { state.transcribingVoiceMessageIds - it }
                    ?: state.transcribingVoiceMessageIds
            )
            AiOperationType.TRANSLATE_MESSAGE -> state.copy(
                translatingMessageIds = operation.targetMessageId?.let { state.translatingMessageIds - it }
                    ?: state.translatingMessageIds
            )
            AiOperationType.SUMMARIZE_MESSAGES -> {
                val operationJob = aiOperationJobs[operation.id]
                val newerSummaryRunning = manualSummaryJob?.let { summaryJob ->
                    summaryJob.isActive && summaryJob !== operationJob
                } == true
                if (newerSummaryRunning) state else state.copy(
                    isAiWorking = false,
                    aiSummaryScope = null,
                    aiSummaryMessageCount = 0
                )
            }
            AiOperationType.ANALYZE_IMAGE -> state.copy(
                isAiWorking = false,
                analyzingImageMessageIds = operation.targetMessageId?.let { state.analyzingImageMessageIds - it }
                    ?: state.analyzingImageMessageIds,
                aiImageAnalysisMode = if (
                    operation.targetMessageId != null &&
                    state.analyzingImageMessageIds.contains(operation.targetMessageId)
                ) null else state.aiImageAnalysisMode
            )
            AiOperationType.ANALYZE_FILE -> state.copy(
                isAiWorking = false,
                analyzingFileMessageIds = operation.targetMessageId?.let { state.analyzingFileMessageIds - it }
                    ?: state.analyzingFileMessageIds,
                aiFileAnalysisMode = if (
                    operation.targetMessageId != null &&
                    state.analyzingFileMessageIds.contains(operation.targetMessageId)
                ) null else state.aiFileAnalysisMode,
                aiFileAnalysisName = if (
                    operation.targetMessageId != null &&
                    state.analyzingFileMessageIds.contains(operation.targetMessageId)
                ) null else state.aiFileAnalysisName
            )
            else -> state
        }
    }
}
