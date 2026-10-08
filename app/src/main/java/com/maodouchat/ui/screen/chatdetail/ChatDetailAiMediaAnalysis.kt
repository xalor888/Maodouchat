package com.maodouchat.ui.screen.chatdetail

import androidx.core.net.toUri
import com.maodouchat.R
import com.maodouchat.data.local.entity.AiOperationError
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.util.MediaCache
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

// AI 媒体分析：文件分析链路 + resolveAiFileInput（附件解析成 base64 / 规范化 mime 与文件名）留守；
// 语音转写、图片分析已拆出同包 ChatDetailAiVoiceAnalysis / ChatDetailAiImageAnalysis。
// 安全约束（代码里已 enforce）：密聊会话禁止 AI 分析——解密明文不得送服务端 AI；
// 纯文本附件超过 120k 或含 NUL / U+FFFD 时拒绝上传。

internal fun ChatDetailViewModel.analyzeFileMessage(
    messageId: String,
    mode: AiFileAnalysisMode,
    question: String?,
    operationId: String? = null
) {
    // 密聊会话禁止 AI 文件分析：解密明文不得送服务端 AI
    if (_uiState.value.isSecretChat == true) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_chat_ai_blocked)) }
        return
    }
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.AI_MASTER)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.ai_analyze_file_disabled)) }
        if (operationId != null) {
            launchTrackedAiOperation(operationId) { failAiOperation(operationId, AiOperationError.CONTEXT_MISSING) }
        }
        return
    }
    if (_uiState.value.analyzingFileMessageIds.contains(messageId)) {
        launchTrackedAiOperation(operationId) {
            failAiOperation(operationId, AiOperationError.CONTEXT_MISSING)
        }
        return
    }
    val message = _uiState.value.messages.firstOrNull { it.id == messageId && it.type == MessageType.FILE }
    if (message == null) {
        launchTrackedAiOperation(operationId) {
            failAiOperation(operationId, AiOperationError.CONTEXT_MISSING)
        }
        return
    }
    if (mode == AiFileAnalysisMode.QUESTION && question.isNullOrBlank()) {
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
                analyzingFileMessageIds = it.analyzingFileMessageIds + messageId,
                aiFileAnalysisResult = null,
                aiFileAnalysisMode = mode,
                aiFileAnalysisName = message.parsedMeta().fileName,
                groupEncryptionWarning = null
            )
        }
        val prepared = withContext(Dispatchers.IO) {
            val localMessage = ensureLocalAttachment(message).getOrNull() ?: return@withContext null
            val uri = runCatching { localMessage.parsedContent().toUri() }.getOrNull() ?: return@withContext null
            val base64 = MediaCache.uriToRawBase64(getApplication(), uri) ?: return@withContext null
            resolveAiFileInput(localMessage, base64)
        }
        requireAiRequestCurrent(request)
        if (prepared == null) {
            failAiOperation(operationId, AiOperationError.CONTEXT_MISSING)
            _uiState.update {
                it.copy(
                    isAiWorking = false,
                    analyzingFileMessageIds = it.analyzingFileMessageIds - messageId,
                    aiFileAnalysisMode = null,
                    aiFileAnalysisName = null,
                    groupEncryptionWarning = text(R.string.chat_ai_file_unsupported)
                )
            }
            return@launchTrackedAiOperation
        }
        requireAiRequestCurrent(request)
        com.maodouchat.ai.agent.LocalAiGateway.analyzeFile(
            context = getApplication(),
            fileName = prepared.fileName,
            mimeType = prepared.mimeType,
            fileBase64 = prepared.base64,
            mode = mode.wireValue,
            question = question
        ).fold(
            onSuccess = { analysis ->
                requireAiRequestCurrent(request)
                if (analysis.isBlank()) {
                    failAiOperation(operationId, AiOperationError.EMPTY_RESULT)
                    _uiState.update {
                        it.copy(
                            isAiWorking = false,
                            analyzingFileMessageIds = it.analyzingFileMessageIds - messageId,
                            aiFileAnalysisMode = null,
                            aiFileAnalysisName = null,
                            groupEncryptionWarning = text(R.string.chat_ai_file_failed)
                        )
                    }
                    return@fold
                }
                val resultText = analysis.trim().take(8_000)
                val analysisKey = if (mode == AiFileAnalysisMode.QUESTION) {
                    "question:" + (question?.trim()?.take(120) ?: "default")
                } else {
                    mode.wireValue
                }
                val current = _uiState.value.messages.firstOrNull { it.id == messageId } ?: message
                val currentMeta = current.parsedMeta()
                val updatedMeta = currentMeta.copy(
                    aiFileAnalyses = currentMeta.aiFileAnalyses + (analysisKey to resultText),
                    preferredFileAnalysisMode = mode.wireValue,
                    aiFileLastQuestion = if (mode == AiFileAnalysisMode.QUESTION) question?.trim()?.take(500) else currentMeta.aiFileLastQuestion
                )
                val updated = current.copy(
                    content = composeContentWithMeta(current.parsedContent(), updatedMeta),
                    meta = updatedMeta
                )
                if (!commitAiMessageResult(operationId, updated, request.userId, request.chatId)) return@fold
                val displayFileResult = com.maodouchat.ai.AiPromptSafetyPolicy
                    .annotateIfPrivilegedHallucination(
                        resultText,
                        text(R.string.chat_ai_privilege_hallucination_disclaimer)
                    )
                _uiState.update { state ->
                    state.copy(
                        isAiWorking = false,
                        analyzingFileMessageIds = state.analyzingFileMessageIds - messageId,
                        messages = state.messages.map { if (it.id == messageId) updated else it },
                        aiFileAnalysisResult = displayFileResult,
                        aiFileAnalysisMode = mode,
                        aiFileAnalysisName = prepared.fileName.take(120)
                    )
                }
            },
            onFailure = { error ->
                requireAiRequestCurrent(request)
                failAiOperation(operationId, aiOperationErrorCode(error))
                _uiState.update {
                    it.copy(
                        isAiWorking = false,
                        analyzingFileMessageIds = it.analyzingFileMessageIds - messageId,
                        aiFileAnalysisMode = null,
                        aiFileAnalysisName = null,
                        groupEncryptionWarning = error.message ?: text(R.string.chat_ai_file_failed)
                    )
                }
            }
        )
    }
}

internal data class PreparedAiFile(val base64: String, val fileName: String, val mimeType: String)

internal fun ChatDetailViewModel.resolveAiFileInput(message: Message, base64: String): PreparedAiFile? {
    val metadata = message.parsedMeta()
    val storedName = metadata.fileName?.trim()?.takeIf(String::isNotBlank)
    val cachedName = runCatching { message.parsedContent().toUri().lastPathSegment?.substringAfterLast('/') }.getOrNull()
    val candidateName = storedName ?: cachedName ?: "document"
    val extension = candidateName.substringAfterLast('.', "").lowercase()
    val canonicalMime = when (extension) {
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "md", "markdown" -> "text/markdown"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "xml" -> "application/xml"
        else -> null
    }
    if (canonicalMime != null) return PreparedAiFile(base64, candidateName.take(120), canonicalMime)

    val bytes = runCatching { android.util.Base64.decode(base64, android.util.Base64.NO_WRAP) }.getOrNull() ?: return null
    if (bytes.size >= 5 && bytes.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))) {
        return PreparedAiFile(base64, "document.pdf", "application/pdf")
    }
    val textContent = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return null
    if (textContent.isBlank() || textContent.length > 120_000 || '\u0000' in textContent || '\uFFFD' in textContent) return null
    return PreparedAiFile(base64, "document.txt", "text/plain")
}
