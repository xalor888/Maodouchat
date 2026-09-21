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

