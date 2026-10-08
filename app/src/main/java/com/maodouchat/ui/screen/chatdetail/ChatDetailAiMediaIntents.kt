package com.maodouchat.ui.screen.chatdetail

import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.data.model.MessageType
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

fun ChatDetailViewModel.requestAiImageAnalysis(messageId: String, mode: AiImageAnalysisMode) {
    if (!isAiAllowed()) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_chat_ai_blocked)) }
        return
    }
    val message = _uiState.value.messages.firstOrNull { it.id == messageId } ?: return
    if (message.type != MessageType.IMAGE || _uiState.value.isAiWorking) return
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.AI_MASTER)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.ai_analyze_image_disabled)) }
        return
    }
    if (!_uiState.value.aiEnabled) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_disabled_warning)) }
        return
    }
    val cached = message.parsedMeta().aiImageAnalyses[mode.wireValue]?.trim()?.takeIf(String::isNotBlank)
    if (cached != null) {
        val updatedMeta = message.parsedMeta().copy(preferredImageAnalysisMode = mode.wireValue)
        val updated = message.copy(
            content = composeContentWithMeta(message.parsedContent(), updatedMeta),
            meta = updatedMeta
        )
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { if (it.id == messageId) updated else it },
                aiImageAnalysisResult = cached,
                aiImageAnalysisMode = mode
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            messageRepo.insertMessage(updated)
        }
        return
    }
    runAiWithConsent(PendingAiAction.AnalyzeImage(messageId, mode))
}

fun ChatDetailViewModel.clearAiImageAnalysis() {
    _uiState.update { it.copy(aiImageAnalysisResult = null, aiImageAnalysisMode = null) }
}

fun ChatDetailViewModel.requestAiFileAnalysis(messageId: String, mode: AiFileAnalysisMode, question: String? = null) {
    if (!isAiAllowed()) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_chat_ai_blocked)) }
        return
    }
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.AI_MASTER)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.ai_analyze_file_disabled)) }
        return
    }
    val message = _uiState.value.messages.firstOrNull { it.id == messageId } ?: return
    if (message.type != MessageType.FILE || _uiState.value.isAiWorking) return
    val normalizedQuestion = question?.trim()?.take(500)
    if (mode == AiFileAnalysisMode.QUESTION && normalizedQuestion.isNullOrBlank()) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_file_question_empty)) }
        return
    }
    if (!_uiState.value.aiEnabled) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_ai_disabled_warning)) }
        return
    }
    val analysisKey = if (mode == AiFileAnalysisMode.QUESTION) {
        "question:" + (normalizedQuestion?.take(120) ?: "default")
    } else {
        mode.wireValue
    }
    val cached = message.parsedMeta().aiFileAnalyses[analysisKey]?.trim()?.takeIf(String::isNotBlank)
    if (cached != null) {
        val updatedMeta = message.parsedMeta().copy(
            preferredFileAnalysisMode = mode.wireValue,
            aiFileLastQuestion = if (mode == AiFileAnalysisMode.QUESTION) normalizedQuestion else message.parsedMeta().aiFileLastQuestion
        )
        val updated = message.copy(
            content = composeContentWithMeta(message.parsedContent(), updatedMeta),
            meta = updatedMeta
        )
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { if (it.id == messageId) updated else it },
                aiFileAnalysisResult = cached,
                aiFileAnalysisMode = mode,
                aiFileAnalysisName = message.parsedMeta().fileName
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            messageRepo.insertMessage(updated)
        }
        return
    }
    runAiWithConsent(PendingAiAction.AnalyzeFile(messageId, mode, normalizedQuestion))
}

fun ChatDetailViewModel.clearAiFileAnalysis() {
    _uiState.update {
        it.copy(aiFileAnalysisResult = null, aiFileAnalysisMode = null, aiFileAnalysisName = null)
    }
}
