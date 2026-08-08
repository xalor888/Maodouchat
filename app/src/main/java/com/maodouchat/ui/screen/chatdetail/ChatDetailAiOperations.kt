package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.ai.AiPrivacyPreferences
import com.maodouchat.ai.AiRetryPolicy
import com.maodouchat.data.local.entity.AiOperationEntity
import com.maodouchat.data.local.entity.AiOperationError
import com.maodouchat.data.local.entity.AiOperationParameters
import com.maodouchat.data.local.entity.AiOperationState
import com.maodouchat.data.local.entity.AiOperationType
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.network.ApiService
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * 从 ChatDetailViewModel.kt 拆分的 AI 操作管线。
 * 管理 AI 操作的排队、执行、重试、取消和 UI 状态清理。
 * 作为 ChatDetailViewModel 的扩展函数，访问 internal 成员。
 */

    internal fun ChatDetailViewModel.loadAiSettings() {
        val ownerUserId = currentUserId
        if (token.isBlank() || ownerUserId.isBlank()) {
            aiSettingsLoaded = true
            _uiState.update { it.copy(aiEnabled = false) }
            return
        }
        viewModelScope.launch {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                aiSettingsLoaded = true
                return@launch
            }
            val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
            ApiService.getAiSettings(liveToken, activeChatId).fold(
                onSuccess = { settings ->
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = ownerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        return@fold
                    }
                    aiSettingsLoaded = true
                    _uiState.update { it.copy(aiEnabled = settings.effectiveEnabled) }
                    maybeGenerateUnreadSummary(_uiState.value.messages)
                },
                onFailure = {
                    // Fail closed: do not leave AI in an unknown half-loaded state.
                    aiSettingsLoaded = true
                    _uiState.update { it.copy(aiEnabled = false) }
                }
            )
        }
    }

    internal fun ChatDetailViewModel.runAiWithConsent(action: PendingAiAction) {
        if (com.maodouchat.ai.AiPrivacyPreferences.consentAccepted(app)) {
            executeAiAction(action)
        } else {
            pendingAiAction = action
            pendingAiOperationRetryId = null
            _uiState.update { it.copy(showAiConsentDialog = true) }
        }
    }

    internal fun ChatDetailViewModel.executeAiAction(action: PendingAiAction) {
        val ownerUserId = tokenManager.getUserId().orEmpty()
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

    fun ChatDetailViewModel.retryAiOperation(operationId: String) {
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

    internal suspend fun ChatDetailViewModel.pumpAiOperationQueue() {
        aiOperationQueueMutex.withLock {
            val ownerUserId = tokenManager.getUserId()?.takeIf(String::isNotBlank) ?: return
            if (!_uiState.value.aiEnabled || !com.maodouchat.ai.AiPrivacyPreferences.consentAccepted(app)) return
            if (aiOperationRepo.getRunning(ownerUserId, activeChatId) != null) return
            val next = aiOperationRepo.getNextQueued(ownerUserId, activeChatId) ?: return
            val action = restoreAiAction(next)
            if (action == null) {
                aiOperationRepo.markFailed(next.id, AiOperationError.CONTEXT_MISSING)
            } else if (aiOperationRepo.markRunning(next.id)) {
                withContext(Dispatchers.Main) { dispatchAiAction(action, next.id) }
                return
            } else {
                return
            }
        }
        pumpAiOperationQueue()
    }

    internal suspend fun ChatDetailViewModel.restoreAiAction(operation: AiOperationEntity): PendingAiAction? {
        val parameters = runCatching {
            aiOperationJson.decodeFromString(AiOperationParameters.serializer(), operation.parametersJson)
        }.getOrNull() ?: return null
        return when (operation.type) {
            AiOperationType.TRANSCRIBE_VOICE -> {
                val messageId = operation.targetMessageId ?: return null
                ensureAiOperationMessage(messageId)?.takeIf { it.type == MessageType.VOICE }
                    ?: return null
                PendingAiAction.TranscribeVoice(messageId)
            }
            AiOperationType.TRANSLATE_MESSAGE -> {
                val messageId = operation.targetMessageId ?: return null
                ensureAiOperationMessage(messageId)?.takeIf { it.type == MessageType.TEXT }
                    ?: return null
                val targetLanguage = parameters.targetLanguage?.takeIf(String::isNotBlank) ?: return null
                PendingAiAction.TranslateMessage(messageId, targetLanguage)
            }
            AiOperationType.SUMMARIZE_MESSAGES -> {
                val scope = parameters.summaryScope?.let { value ->
                    AiSummaryScope.entries.firstOrNull { it.name == value }
                } ?: return null
                val messages = parameters.messageIds.mapNotNull { ensureAiOperationMessage(it) }
                if (messages.isEmpty()) return null
                PendingAiAction.Summarize(scope, messages)
            }
            AiOperationType.ANALYZE_IMAGE -> {
                val messageId = operation.targetMessageId ?: return null
                ensureAiOperationMessage(messageId)?.takeIf { it.type == MessageType.IMAGE }
                    ?: return null
                val mode = AiImageAnalysisMode.entries.firstOrNull {
                    it.wireValue == parameters.analysisMode
                } ?: return null
                PendingAiAction.AnalyzeImage(messageId, mode)
            }
            AiOperationType.ANALYZE_FILE -> {
                val messageId = operation.targetMessageId ?: return null
                ensureAiOperationMessage(messageId)?.takeIf { it.type == MessageType.FILE }
                    ?: return null
                val mode = AiFileAnalysisMode.entries.firstOrNull {
                    it.wireValue == parameters.analysisMode
                } ?: return null
                val question = parameters.question?.trim()?.takeIf(String::isNotBlank)
                if (mode == AiFileAnalysisMode.QUESTION && question.isNullOrBlank()) return null
                PendingAiAction.AnalyzeFile(messageId, mode, question)
            }
            else -> null
        }
    }

    internal suspend fun ChatDetailViewModel.ensureAiOperationMessage(messageId: String): Message? {
        _uiState.value.messages.firstOrNull { it.id == messageId }?.let { return it }
        val cached = withContext(Dispatchers.IO) { messageRepo.getMessageById(messageId) }
            ?.takeIf { it.chatId == activeChatId }
            ?: return null
        _uiState.update { state -> state.copy(messages = mergeMessages(state.messages, listOf(cached))) }
        return cached
    }

    internal fun ChatDetailViewModel.launchTrackedAiOperation(
        operationId: String?,
        startImmediately: Boolean = true,
        block: suspend () -> Unit
    ): kotlinx.coroutines.Job {
        val job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                if (operationId != null) {
                    val operation = withContext(Dispatchers.IO) { aiOperationRepo.get(operationId) }
                    if (operation?.state != AiOperationState.RUNNING) return@launch
                }
                block()
            } catch (error: kotlinx.coroutines.CancellationException) {
                // Job cancel must still persist CANCELLED + clear loading chips; plain withContext is cancelled.
                if (operationId != null) {
                    val operation = withContext(Dispatchers.IO + NonCancellable) {
                        val current = aiOperationRepo.get(operationId)
                        if (current?.state == AiOperationState.RUNNING) {
                            aiOperationRepo.markCancelled(operationId)
                        }
                        current
                    }
                    if (operation != null) {
                        withContext(Dispatchers.Main.immediate + NonCancellable) {
                            clearAiOperationUi(operation)
                        }
                    }
                }
                throw error
            } finally {
                if (operationId != null) {
                    withContext(Dispatchers.IO + NonCancellable) { pumpAiOperationQueue() }
                }
            }
        }
        if (operationId != null) {
            aiOperationJobs[operationId] = job
            job.invokeOnCompletion { aiOperationJobs.remove(operationId, job) }
        }
        if (startImmediately) job.start()
        return job
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

    internal suspend fun ChatDetailViewModel.completeAiOperation(operationId: String?): Boolean =
        operationId == null || withContext(Dispatchers.IO) {
            aiAutoRetryJobs.remove(operationId)?.cancel()
            aiAutoRetryAt.remove(operationId)
            aiOperationRepo.markSucceeded(operationId)
        }

    internal suspend fun ChatDetailViewModel.failAiOperation(operationId: String?, errorCode: String) {
        if (operationId == null) return
        withContext(Dispatchers.IO) {
            val operation = aiOperationRepo.get(operationId) ?: return@withContext
            val decision = AiRetryPolicy.decide(errorCode, operation.attempts)
            if (decision.shouldRetry) {
                val retryAt = System.currentTimeMillis() + decision.delayMs
                aiAutoRetryAt[operationId] = retryAt
                _uiState.update { state ->
                    state.copy(aiOperations = state.aiOperations.map {
                        if (it.id == operationId) {
                            it.copy(
                                state = AiOperationState.FAILED,
                                lastErrorCode = errorCode,
                                nextRetryAtMs = retryAt
                            )
                        } else it
                    })
                }
                aiOperationRepo.markFailed(operationId, errorCode)
                val retryJob = viewModelScope.launch {
                    delay(decision.delayMs)
                    aiAutoRetryAt.remove(operationId)
                    val requeued = withContext(Dispatchers.IO) { aiOperationRepo.markQueued(operationId) }
                    if (requeued) withContext(Dispatchers.IO) { pumpAiOperationQueue() }
                }
                aiAutoRetryJobs.put(operationId, retryJob)?.cancel()
                retryJob.invokeOnCompletion { aiAutoRetryJobs.remove(operationId, retryJob) }
            } else {
                aiAutoRetryAt.remove(operationId)
                aiOperationRepo.markFailed(operationId, errorCode)
            }
        }
    }

    internal fun ChatDetailViewModel.aiOperationErrorCode(error: Throwable): String =
        classifyAiOperationErrorDetailed(error).persistedCode
