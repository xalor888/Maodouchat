package com.maodouchat.ui.screen.chatdetail

import androidx.core.net.toUri
import com.maodouchat.R
import com.maodouchat.data.local.entity.AiOperationError
import com.maodouchat.data.model.MessageType
import com.maodouchat.util.ImagePicker
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

// 图片分析链路（从 ChatDetailAiMediaAnalysis.kt 拆出，纯搬移）。

internal fun ChatDetailViewModel.analyzeImageMessage(messageId: String, mode: AiImageAnalysisMode, operationId: String? = null) {
    // 密聊会话禁止 AI 图像分析：解密明文不得送服务端 AI
    if (_uiState.value.isSecretChat == true) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_chat_ai_blocked)) }
        return
    }
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.AI_MASTER)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.ai_analyze_image_disabled)) }
        if (operationId != null) {
            launchTrackedAiOperation(operationId) { failAiOperation(operationId, AiOperationError.CONTEXT_MISSING) }
        }
        return
    }
    if (_uiState.value.analyzingImageMessageIds.contains(messageId)) {
        launchTrackedAiOperation(operationId) {
            failAiOperation(operationId, AiOperationError.CONTEXT_MISSING)
        }
        return
    }
    val message = _uiState.value.messages.firstOrNull { it.id == messageId && it.type == MessageType.IMAGE }
    if (message == null) {
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
                isAiWorking = true,
                analyzingImageMessageIds = it.analyzingImageMessageIds + messageId,
                aiImageAnalysisResult = null,
                aiImageAnalysisMode = mode,
                groupEncryptionWarning = null
            )
        }
        val imageBase64 = withContext(Dispatchers.IO) {
            try {
                val localMessage = ensureLocalAttachment(message).getOrThrow()
                ImagePicker.uriToBase64(
                    context = getApplication(),
                    uri = localMessage.parsedContent().toUri(),
                    maxWidth = 1_024,
                    quality = 72
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
        requireAiRequestCurrent(request)
        if (imageBase64.isNullOrBlank()) {
            failAiOperation(operationId, AiOperationError.CONTEXT_MISSING)
            _uiState.update {
                it.copy(
                    isAiWorking = false,
                    analyzingImageMessageIds = it.analyzingImageMessageIds - messageId,
                    aiImageAnalysisMode = null,
                    groupEncryptionWarning = text(R.string.chat_ai_image_unavailable)
                )
            }
            return@launchTrackedAiOperation
        }
        requireAiRequestCurrent(request)
        com.maodouchat.ai.agent.LocalAiGateway.analyzeImage(
            getApplication(),
            imageBase64,
            mode.wireValue
        ).fold(
            onSuccess = { analysis ->
                requireAiRequestCurrent(request)
                if (analysis.isBlank()) {
                    failAiOperation(operationId, AiOperationError.EMPTY_RESULT)
                    _uiState.update {
                        it.copy(
                            isAiWorking = false,
                            analyzingImageMessageIds = it.analyzingImageMessageIds - messageId,
                            aiImageAnalysisMode = null,
                            groupEncryptionWarning = text(R.string.chat_ai_image_failed)
                        )
                    }
                    return@fold
                }
                val resultText = analysis.trim().take(6_000)
                val current = _uiState.value.messages.firstOrNull { it.id == messageId } ?: message
                val currentMeta = current.parsedMeta()
                val updatedMeta = currentMeta.copy(
                    aiImageAnalyses = currentMeta.aiImageAnalyses + (mode.wireValue to resultText),
                    preferredImageAnalysisMode = mode.wireValue
                )
                val updated = current.copy(
                    content = composeContentWithMeta(current.parsedContent(), updatedMeta),
                    meta = updatedMeta
                )
                if (!commitAiMessageResult(operationId, updated, request.userId, request.chatId)) return@fold
                val displayImageResult = com.maodouchat.ai.AiPromptSafetyPolicy
                    .annotateIfPrivilegedHallucination(
                        resultText,
                        text(R.string.chat_ai_privilege_hallucination_disclaimer)
                    )
                _uiState.update { state ->
                    state.copy(
                        isAiWorking = false,
                        analyzingImageMessageIds = state.analyzingImageMessageIds - messageId,
                        messages = state.messages.map { if (it.id == messageId) updated else it },
                        aiImageAnalysisResult = displayImageResult,
                        aiImageAnalysisMode = mode
                    )
                }
            },
            onFailure = { error ->
                requireAiRequestCurrent(request)
                failAiOperation(operationId, aiOperationErrorCode(error))
                _uiState.update {
                    it.copy(
                        isAiWorking = false,
                        analyzingImageMessageIds = it.analyzingImageMessageIds - messageId,
                        aiImageAnalysisMode = null,
                        groupEncryptionWarning = error.message ?: text(R.string.chat_ai_image_failed)
                    )
                }
            }
        )
    }
}
