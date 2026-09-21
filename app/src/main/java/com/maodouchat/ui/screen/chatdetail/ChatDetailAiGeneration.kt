package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.util.RuntimeFlags
import com.maodouchat.R
import com.maodouchat.ai.AiCostVisibilityPolicy
import com.maodouchat.ai.AiPromptSafetyPolicy
import com.maodouchat.ai.AiRetryPolicy
import com.maodouchat.ai.AiWritingStylePreferences
import com.maodouchat.data.local.entity.AiOperationState
import com.maodouchat.data.local.entity.AiOperationError
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageType
import com.maodouchat.network.ApiService
import com.maodouchat.network.AiContextMessage
import com.maodouchat.network.AiSemanticSearchCandidate
import com.maodouchat.data.model.semanticSearchText
import com.maodouchat.util.MediaCache
import com.maodouchat.util.ImagePicker
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import android.net.Uri

/**
 * 从 ChatDetailViewModel.kt 拆分的 AI 生成函数。
 * 包含群助手、语义搜索、改写、回复建议、摘要、语音转写、图片/文件分析、翻译等。
 * 作为 ChatDetailViewModel 的扩展函数，访问 internal 成员。
 */

internal data class AiRequestSnapshot(val userId: String, val chatId: String)

internal fun ChatDetailViewModel.captureAiRequestSnapshot(): AiRequestSnapshot? {
    val userId = tokenManager.getUserId().orEmpty()
    val chatId = activeChatId
    val state = _uiState.value
    if (chatId.isBlank() || state.currentUserId != userId || state.chat?.id != chatId ||
        !com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = userId,
            liveToken = tokenManager.getToken(),
            liveUserId = tokenManager.getUserId(),
        )
    ) {
        return null
    }
    return AiRequestSnapshot(userId = userId, chatId = chatId)
}

internal fun ChatDetailViewModel.isAiRequestCurrent(snapshot: AiRequestSnapshot): Boolean =
    activeChatId == snapshot.chatId &&
        _uiState.value.currentUserId == snapshot.userId &&
        _uiState.value.chat?.id == snapshot.chatId &&
        com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = snapshot.userId,
            liveToken = tokenManager.getToken(),
            liveUserId = tokenManager.getUserId(),
        )

internal fun ChatDetailViewModel.requireAiRequestCurrent(snapshot: AiRequestSnapshot) {
    if (!isAiRequestCurrent(snapshot)) {
        throw kotlinx.coroutines.CancellationException("ai_request_context_changed")
    }
}

    internal fun ChatDetailViewModel.groupAssistant(query: String, mode: String) {
        // 密聊会话禁止群 AI 助手：解密明文不得送服务端 AI
        if (_uiState.value.isSecretChat == true) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_chat_ai_blocked)) }
            return
        }
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.AI_MASTER)) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.ai_group_assistant_disabled)) }
            return
        }
        val contextMessages = buildGroupAiContextMessages()
        if (contextMessages.isEmpty()) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_group_ai_no_context)) }
            return
        }
        val request = captureAiRequestSnapshot()
        if (request == null) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.error_session_expired)) }
            return
        }
        groupAiJob?.cancel()
        val generation = groupAiGate.next()
        val currentCommand = parseGroupAiCommand(_uiState.value.inputText)
        _uiState.update {
            it.copy(
                inputText = if (currentCommand == query) "" else it.inputText,
                isAiWorking = true,
                groupAiAnswer = null,
                groupAiQuestion = query,
                groupAiMode = mode,
                groupAiTasks = emptyList(),
                isSavingGroupAiTasks = false,
                groupAiTasksSaved = false,
                groupAiTaskSaveError = null,
                groupAiAnswerShared = false,
                groupEncryptionWarning = null
            )
        }
        if (currentCommand == query) clearDraft()
        val job = viewModelScope.launch {
            try {
                if (!groupAiGate.isCurrent(generation)) return@launch
                requireAiRequestCurrent(request)
                com.maodouchat.ai.agent.LocalAiGateway.groupAssistant(
                    getApplication(),
                    query,
                    contextMessages,
                    mode
                ).fold(
                    onSuccess = { answer ->
                        if (!groupAiGate.isCurrent(generation)) return@fold
                        requireAiRequestCurrent(request)
                        val tasks = if (mode == "tasks") {
                            answer.lineSequence()
                                .map { it.trim().trimStart('-', '*', '•') }
                                .filter { it.isNotBlank() }
                                .take(30)
                                .map { line ->
                                    com.maodouchat.network.AiGroupTask(title = line.take(300))
                                }
                                .toList()
                        } else emptyList()
                        _uiState.update {
                            it.copy(
                                groupAiAnswer = com.maodouchat.ai.AiPromptSafetyPolicy
                                    .annotateIfPrivilegedHallucination(
                                        answer.trim().take(4_000),
                                        text(R.string.chat_ai_privilege_hallucination_disclaimer)
                                    ),
                                groupAiMode = mode,
                                groupAiTasks = tasks,
                                isAiWorking = false
                            )
                        }
                    },
                    onFailure = { error ->
                        if (!groupAiGate.isCurrent(generation)) return@fold
                        requireAiRequestCurrent(request)
                        _uiState.update {
                            it.copy(
                                inputText = if (it.inputText.isBlank()) "@AI $query" else it.inputText,
                                isAiWorking = false,
                                groupEncryptionWarning = error.message ?: text(R.string.chat_group_ai_failed)
                            )
                        }
                        scheduleDraftPersistence(_uiState.value.inputText)
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (groupAiGate.isCurrent(generation) && isAiRequestCurrent(request)) {
                    _uiState.update { it.copy(isAiWorking = false) }
                }
                throw error
            }
        }
        groupAiJob = job
        job.invokeOnCompletion {
            if (groupAiJob === job) groupAiJob = null
        }
    }

    internal fun ChatDetailViewModel.semanticSearch(query: String, candidateMessageIds: List<String>) {
        val candidateIdSet = candidateMessageIds.toHashSet()
        val state = _uiState.value
        val candidates = state.messages
            .asSequence()
            .filter { it.id in candidateIdSet }
            .mapNotNull { message ->
                val searchableText = message.semanticSearchText()
                if (searchableText.isBlank()) return@mapNotNull null
                val sender = if (message.senderId == currentUserId) {
                    "me"
                } else {
                    state.chat?.participants?.firstOrNull { it.id == message.senderId }?.displayName
                        ?: state.contact.displayName
                }
                AiSemanticSearchCandidate(
                    messageId = message.id,
                    sender = sender.take(80),
                    text = searchableText.take(700),
                    timestamp = message.timestamp
                )
            }
            .take(80)
            .toList()
        if (candidates.isEmpty()) {
            _uiState.update { it.copy(semanticSearchError = text(R.string.chat_semantic_search_no_context)) }
            return
        }
        val request = captureAiRequestSnapshot()
        if (request == null) {
            _uiState.update { it.copy(semanticSearchError = text(R.string.error_session_expired)) }
            return
        }
        semanticSearchJob?.cancel()
        val generation = semanticSearchGate.next()
        val job = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    semanticSearchResultIds = emptyList(),
                    semanticSearchQuery = query,
                    isSemanticSearching = true,
                    semanticSearchError = null
                )
            }
            try {
                requireAiRequestCurrent(request)
                val ranked = com.maodouchat.ai.agent.LocalAiGateway.rankSemantic(
                    query,
                    candidates.map { it.messageId to it.text }
                )
                requireAiRequestCurrent(request)
                if (!semanticSearchGate.isCurrent(generation)) return@launch
                val allowedIds = candidates.mapTo(hashSetOf()) { it.messageId }
                val resultIds = ranked.filter { it in allowedIds }.distinct().take(12)
                _uiState.update {
                    it.copy(
                        semanticSearchResultIds = resultIds,
                        semanticSearchQuery = query,
                        isSemanticSearching = false,
                        semanticSearchError = if (resultIds.isEmpty()) {
                            text(R.string.chat_semantic_search_no_context)
                        } else null
                    )
                }
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (semanticSearchGate.isCurrent(generation) && isAiRequestCurrent(request)) {
                    _uiState.update {
                        it.copy(
                            isSemanticSearching = false,
                            semanticSearchError = error.message ?: text(R.string.chat_semantic_search_failed)
                        )
                    }
                }
            }
        }
        semanticSearchJob = job
        job.invokeOnCompletion { error ->
            if (semanticSearchJob === job) semanticSearchJob = null
            // Cancelled mid-request (clear / newer generation) must not leave spinner stuck.
            if (error is kotlinx.coroutines.CancellationException &&
                semanticSearchGate.isCurrent(generation) &&
                isAiRequestCurrent(request) &&
                _uiState.value.isSemanticSearching
            ) {
                _uiState.update { it.copy(isSemanticSearching = false) }
            }
        }
    }

    internal fun ChatDetailViewModel.buildGroupAiContextMessages(): List<AiContextMessage> {
        return buildPlainAiContextMessages(
            messages = _uiState.value.messages,
            senders = aiContextSenders(),
            limit = 30
        )
    }

    internal fun ChatDetailViewModel.inferGroupAiMode(query: String): String {
        val normalized = query.trim().lowercase()
        return when {
            normalized.startsWith("总结") || normalized.startsWith("概括") || normalized.startsWith("summary") || normalized.startsWith("summarize") -> "summary"
            normalized.startsWith("决策") || normalized.startsWith("决定") || normalized.startsWith("decisions") -> "decisions"
            normalized.startsWith("待办") || normalized.startsWith("任务") || normalized.startsWith("tasks") || normalized.startsWith("todo") -> "tasks"
            normalized.startsWith("时间线") || normalized.startsWith("时间轴") || normalized.startsWith("timeline") || normalized.startsWith("chronology") -> "timeline"
            normalized.startsWith("风险") || normalized.startsWith("隐患") || normalized.startsWith("risk") || normalized.startsWith("blocker") -> "risks"
            else -> "answer"
        }
    }

    internal fun ChatDetailViewModel.parseGroupAiCommand(value: String): String? {
        val trimmed = value.trim()
        if (!trimmed.startsWith("@AI", ignoreCase = true)) return null
        if (trimmed.length > 3 && !trimmed[3].isWhitespace()) return null
        return trimmed.drop(3).trim()
    }

    internal fun ChatDetailViewModel.rewriteDraft(mode: String, targetLanguage: String?) {
        val draft = _uiState.value.inputText.trim()
        if (draft.isBlank()) return
        val request = captureAiRequestSnapshot()
        if (request == null) {
            _uiState.update { it.copy(aiDraftStreamErrorCode = AiOperationError.CONTEXT_MISSING) }
            return
        }
        aiRewriteStreamJob?.cancel()
        val generation = aiRewriteGate.next()
        lastAiRewriteMode = mode
        lastAiRewriteTargetLanguage = targetLanguage
        val buffer = StringBuilder()
        var lastUiUpdateAt = 0L
        _uiState.update {
            it.copy(
                isAiWorking = true,
                aiDraftOriginal = draft,
                aiDraftPreview = "",
                isAiDraftStreaming = true,
                aiDraftStreamErrorCode = null,
                groupEncryptionWarning = null,
                aiSuggestions = emptyList(),
                aiReplyStreamErrorCode = null
            )
        }
        aiRewriteStreamJob = viewModelScope.launch {
            try {
                requireAiRequestCurrent(request)
                com.maodouchat.ai.agent.LocalAiGateway.rewrite(
                    context = getApplication(),
                    text = draft,
                    mode = mode,
                    targetLanguage = targetLanguage
                ) { delta ->
                    if (!aiRewriteGate.isCurrent(generation)) return@rewrite
                    if (!isAiRequestCurrent(request)) {
                        throw kotlinx.coroutines.CancellationException("ai_rewrite_session_changed")
                    }
                    if (delta.isEmpty() || buffer.length >= 4_000) return@rewrite
                    buffer.append(delta.take(4_000 - buffer.length))
                    val now = System.currentTimeMillis()
                    if (now - lastUiUpdateAt >= 32L || '\n' in delta) {
                        lastUiUpdateAt = now
                        val preview = buffer.toString()
                        _uiState.update { state ->
                            if (aiRewriteGate.isCurrent(generation)) state.copy(aiDraftPreview = preview) else state
                        }
                    }
                }.fold(
                    onSuccess = { rewritten ->
                        if (!aiRewriteGate.isCurrent(generation) || !isAiRequestCurrent(request)) return@fold
                        val preview = buffer.toString().ifBlank { rewritten }.trim().take(4_000)
                        _uiState.update {
                            it.copy(
                                aiDraftPreview = preview,
                                isAiDraftStreaming = false,
                                isAiWorking = false,
                                aiDraftStreamErrorCode = if (preview.isBlank()) AiOperationError.EMPTY_RESULT else null
                            )
                        }
                    },
                    onFailure = { error ->
                        if (!aiRewriteGate.isCurrent(generation) || !isAiRequestCurrent(request)) return@fold
                        _uiState.update {
                            it.copy(
                                aiDraftPreview = buffer.toString().trim().take(4_000),
                                isAiDraftStreaming = false,
                                isAiWorking = false,
                                aiDraftStreamErrorCode = aiOperationErrorCode(error)
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (aiRewriteGate.isCurrent(generation) && isAiRequestCurrent(request)) {
                    _uiState.update {
                        it.copy(isAiDraftStreaming = false, isAiWorking = false)
                    }
                }
                throw error
            }
        }
    }

fun ChatDetailViewModel.cancelAiDraftStream() {
        aiRewriteGate.invalidate()
        aiRewriteStreamJob?.cancel()
        aiRewriteStreamJob = null
        _uiState.update { state ->
            if (state.aiDraftPreview.isBlank()) {
                state.copy(
                    aiDraftOriginal = null,
                    aiDraftPreview = "",
                    isAiDraftStreaming = false,
                    aiDraftStreamErrorCode = null,
                    isAiWorking = false
                )
            } else {
                state.copy(
                    isAiDraftStreaming = false,
                    aiDraftStreamErrorCode = "CANCELLED",
                    isAiWorking = false
                )
            }
        }
    }

    fun ChatDetailViewModel.discardAiDraftPreview() {
        val wasStreaming = _uiState.value.isAiDraftStreaming
        aiRewriteGate.invalidate()
        aiRewriteStreamJob?.cancel()
        aiRewriteStreamJob = null
        _uiState.update {
            it.copy(
                aiDraftOriginal = null,
                aiDraftPreview = "",
                isAiDraftStreaming = false,
                aiDraftStreamErrorCode = null,
                isAiWorking = if (wasStreaming) false else it.isAiWorking
            )
        }
    }

    fun ChatDetailViewModel.applyAiDraftPreview() {
        val preview = _uiState.value.aiDraftPreview.trim().take(4_000)
        if (preview.isBlank()) return
        if (_uiState.value.isAiReplyStreaming) cancelAiReplyStream(clearSuggestions = true)
        val wasStreaming = _uiState.value.isAiDraftStreaming
        aiRewriteGate.invalidate()
        aiRewriteStreamJob?.cancel()
        aiRewriteStreamJob = null
        hasUserEditedInput = true
        _uiState.update {
            it.copy(
                inputText = preview,
                aiDraftOriginal = null,
                aiDraftPreview = "",
                isAiDraftStreaming = false,
                aiDraftStreamErrorCode = null,
                isAiWorking = if (wasStreaming) false else it.isAiWorking,
                groupEncryptionWarning = text(R.string.chat_ai_rewrite_success)
            )
        }
        scheduleDraftPersistence(preview)
    }

    fun ChatDetailViewModel.retryAiDraftStream() {
        _uiState.value.aiDraftOriginal?.takeIf(String::isNotBlank) ?: return
        requestAiRewrite(lastAiRewriteMode, lastAiRewriteTargetLanguage)
    }

    fun ChatDetailViewModel.cancelAiReplyStream(clearSuggestions: Boolean = false) {
        val wasStreaming = _uiState.value.isAiReplyStreaming
        aiReplyGate.invalidate()
        aiReplyStreamJob?.cancel()
        aiReplyStreamJob = null
        _uiState.update {
            it.copy(
                isAiReplyStreaming = false,
                isAiWorking = if (wasStreaming) false else it.isAiWorking,
                aiSuggestions = if (clearSuggestions) emptyList() else it.aiSuggestions,
                aiReplyStreamErrorCode = null
            )
        }
    }

    fun ChatDetailViewModel.retryAiReplyStream() = requestAiSuggestions(lastAiReplyTone)



    internal fun ChatDetailViewModel.translateTextMessage(
        messageId: String,
        targetLanguage: String,
        operationId: String? = null
    ) {
        // 密聊会话禁止 AI 翻译：解密明文不得送服务端 AI
        if (_uiState.value.isSecretChat == true) {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_chat_ai_blocked)) }
            return
        }
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.AI_MASTER)) {
            _uiState.update { it.copy(errorMessage = text(R.string.chat_ai_translate_disabled)) }
            return
        }

        if (_uiState.value.translatingMessageIds.contains(messageId)) {
            launchTrackedAiOperation(operationId) {
                failAiOperation(operationId, AiOperationError.CONTEXT_MISSING)
            }
            return
        }
        val message = _uiState.value.messages.firstOrNull { it.id == messageId && (it.type == MessageType.TEXT || it.type == MessageType.MARKDOWN) }
        if (message == null) {
            launchTrackedAiOperation(operationId) {
                failAiOperation(operationId, AiOperationError.CONTEXT_MISSING)
            }
            return
        }
        val sourceText = message.parsedContent().trim()
        if (sourceText.isBlank()) {
            launchTrackedAiOperation(operationId) {
                failAiOperation(operationId, AiOperationError.CONTEXT_MISSING)
            }
            return
        }
        val request = captureAiRequestSnapshot()
        if (request == null) {
            launchTrackedAiOperation(operationId) { failAiOperation(operationId, AiOperationError.CONTEXT_MISSING) }
            return
        }
        launchTrackedAiOperation(operationId) {
            requireAiRequestCurrent(request)
            _uiState.update {
                it.copy(
                    translatingMessageIds = it.translatingMessageIds + messageId,
                    groupEncryptionWarning = null
                )
            }
            requireAiRequestCurrent(request)
            com.maodouchat.ai.agent.LocalAiGateway.translate(
                getApplication(),
                sourceText.take(4_000),
                targetLanguage
            ).fold(
                onSuccess = { translatedRaw ->
                    requireAiRequestCurrent(request)
                    val translated = translatedRaw.trim().take(4_000)
                    if (translated.isBlank()) {
                        failAiOperation(operationId, AiOperationError.EMPTY_RESULT)
                        _uiState.update {
                            it.copy(
                                translatingMessageIds = it.translatingMessageIds - messageId,
                                groupEncryptionWarning = text(R.string.chat_translation_empty)
                            )
                        }
                        return@fold
                    }
                    val current = _uiState.value.messages.firstOrNull { it.id == messageId } ?: message
                    val currentMeta = current.parsedMeta()
                    val updatedMeta = currentMeta.copy(
                        translations = currentMeta.translations + (targetLanguage to translated),
                        preferredTranslationLanguage = targetLanguage
                    )
                    val updated = current.copy(
                        content = composeContentWithMeta(current.parsedContent(), updatedMeta),
                        meta = updatedMeta
                    )
                    if (!commitAiMessageResult(operationId, updated, request.userId, request.chatId)) return@fold
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map { if (it.id == messageId) updated else it },
                            translatingMessageIds = state.translatingMessageIds - messageId,
                            groupEncryptionWarning = text(R.string.chat_translated)
                        )
                    }
                },
                onFailure = { error ->
                    requireAiRequestCurrent(request)
                    failAiOperation(operationId, aiOperationErrorCode(error))
                    _uiState.update {
                        it.copy(
                            translatingMessageIds = it.translatingMessageIds - messageId,
                            groupEncryptionWarning = error.message ?: text(R.string.chat_translation_failed)
                        )
                    }
                }
            )
        }
    }

    internal fun ChatDetailViewModel.maybeGenerateUnreadSummary(messages: List<Message>) {
        val state = _uiState.value
        val unreadCount = state.chat?.unreadCount ?: 0
        if (unreadCount <= 0 || messages.isEmpty() || token.isBlank()) return
        if (
            !aiSettingsLoaded ||
            !state.aiEnabled ||
            !com.maodouchat.ai.AiPrivacyPreferences.userEnabled(app) ||
            !com.maodouchat.ai.AiPrivacyPreferences.consentAccepted(app)
        ) return

        val candidates = messages
            .filter { message ->
                message.senderId != currentUserId &&
                    (message.type == MessageType.TEXT || message.type == MessageType.MARKDOWN) &&
                    !message.parsedMeta().aiAssisted &&
                    message.parsedContent().isNotBlank()
            }
            .sortedBy { it.timestamp }
            .takeLast(unreadCount.coerceIn(1, MAX_AI_SUMMARY_MESSAGES))

        if (candidates.isEmpty()) return
        val first = candidates.first()
        val last = candidates.last()
        val request = captureAiRequestSnapshot() ?: return
        val cacheKey = "${request.chatId}:${first.id}:${last.id}:${candidates.size}"
        // Only skip after a terminal success/definitive failure for this window.
        // Network blips must not permanently suppress auto unread summaries.
        if (unreadSummaryAttemptedForKey == cacheKey) return
        if (unreadSummaryInFlightKey == cacheKey || _uiState.value.isUnreadSummaryLoading) return

        val contextMessages = buildAiSummaryContextMessages(candidates)
        if (contextMessages.isEmpty()) return

        unreadSummaryInFlightKey = cacheKey
        val job = viewModelScope.launch {
            requireAiRequestCurrent(request)
            _uiState.update { it.copy(isUnreadSummaryLoading = true) }
            try {
                val cached = withContext(Dispatchers.IO) { aiSummaryRepo.getSummary(cacheKey) }
                requireAiRequestCurrent(request)
                if (cached != null) {
                    unreadSummaryAttemptedForKey = cacheKey
                    val displaySummary = AiPromptSafetyPolicy.annotateIfPrivilegedHallucination(
                        cached.summary,
                        text(R.string.chat_ai_privilege_hallucination_disclaimer)
                    )
                    _uiState.update {
                        it.copy(
                            unreadAiSummary = displaySummary,
                            unreadAiSummaryCount = cached.messageCount,
                            isUnreadSummaryLoading = false
                        )
                    }
                    return@launch
                }

                requireAiRequestCurrent(request)
                com.maodouchat.ai.agent.LocalAiGateway.summarize(
                    getApplication(),
                    contextMessages,
                    "brief"
                ).fold(
                    onSuccess = { generated ->
                        requireAiRequestCurrent(request)
                        unreadSummaryAttemptedForKey = cacheKey
                        val summary = generated.trim().take(3_000)
                        if (summary.isBlank()) {
                            _uiState.update { it.copy(isUnreadSummaryLoading = false) }
                            return@fold
                        }
                        val saved = withContext(Dispatchers.IO) {
                            aiSummaryRepo.saveSummary(
                                cacheKey = cacheKey,
                                chatId = request.chatId,
                                startMessageId = first.id,
                                endMessageId = last.id,
                                messageCount = candidates.size,
                                summary = summary
                            )
                        }
                        requireAiRequestCurrent(request)
                        val displaySummary = AiPromptSafetyPolicy.annotateIfPrivilegedHallucination(
                            summary,
                            text(R.string.chat_ai_privilege_hallucination_disclaimer)
                        )
                        _uiState.update {
                            it.copy(
                                unreadAiSummary = displaySummary,
                                unreadAiSummaryCount = candidates.size,
                                isUnreadSummaryLoading = false
                            )
                        }
                    },
                    onFailure = { error ->
                        requireAiRequestCurrent(request)
                        // Retryable transport errors leave the key unmarked so a later open can retry.
                        if (!isAmbiguousTransportFailure(error)) {
                            unreadSummaryAttemptedForKey = cacheKey
                        }
                        _uiState.update { it.copy(isUnreadSummaryLoading = false) }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isAiRequestCurrent(request)) {
                    _uiState.update { it.copy(isUnreadSummaryLoading = false) }
                }
                throw error
            } catch (_: Throwable) {
                if (isAiRequestCurrent(request)) {
                    _uiState.update { it.copy(isUnreadSummaryLoading = false) }
                }
            } finally {
                if (unreadSummaryInFlightKey == cacheKey) {
                    unreadSummaryInFlightKey = null
                }
            }
        }
        unreadSummaryJob = job
        job.invokeOnCompletion {
            if (unreadSummaryJob === job) unreadSummaryJob = null
        }
    }

    internal fun ChatDetailViewModel.buildAiContextMessages(limit: Int): List<AiContextMessage> {
        return buildPlainAiContextMessages(
            messages = _uiState.value.messages,
            senders = aiContextSenders(),
            limit = limit
        )
    }

    internal fun ChatDetailViewModel.buildAiContextMessages(messages: List<Message>): List<AiContextMessage> {
        return buildPlainAiContextMessages(
            messages = messages,
            senders = aiContextSenders(),
            limit = messages.size.coerceAtLeast(1)
        )
    }

    internal fun ChatDetailViewModel.aiContextSenders(): AiContextSenders {
        val state = _uiState.value
        val fallback = if (state.chatIsGroup) {
            text(R.string.chat_other_person)
        } else {
            state.contact.displayName.ifBlank { text(R.string.chat_other_person) }
        }
        return AiContextSenders(
            currentUserId = currentUserId,
            currentUserLabel = text(R.string.chat_sender_me),
            fallbackLabel = fallback,
            namesByUserId = state.chat?.participants.orEmpty().associate { it.id to it.displayName }
        )
    }
