package com.maodouchat.ui.screen.chatdetail

import androidx.core.net.toUri
import com.maodouchat.R
import com.maodouchat.data.local.entity.AiOperationError
import com.maodouchat.data.model.MessageType
import com.maodouchat.util.MediaCache
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

// 语音转写链路（从 ChatDetailAiMediaAnalysis.kt 拆出，纯搬移）。

internal fun ChatDetailViewModel.transcribeVoiceMessage(messageId: String, operationId: String? = null) {
    // 密聊会话禁止 AI 转写：解密明文不得送服务端 AI
    if (_uiState.value.isSecretChat == true) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_chat_ai_blocked)) }
        return
    }
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.AI_MASTER)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.ai_transcribe_disabled)) }
        if (operationId != null) {
            launchTrackedAiOperation(operationId) {
                failAiOperation(operationId, AiOperationError.CONTEXT_MISSING)
            }
        }
        return
    }
    if (_uiState.value.transcribingVoiceMessageIds.contains(messageId)) {
        launchTrackedAiOperation(operationId) {
            failAiOperation(operationId, AiOperationError.CONTEXT_MISSING)
        }
        return
    }
    val message = _uiState.value.messages.firstOrNull { it.id == messageId && it.type == MessageType.VOICE }
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
                transcribingVoiceMessageIds = it.transcribingVoiceMessageIds + messageId,
                groupEncryptionWarning = null
            )
        }
        val prepared = withContext(Dispatchers.IO) {
            val localMessage = ensureLocalAttachment(message).getOrNull() ?: return@withContext null
            val voiceUri = runCatching { localMessage.parsedContent().toUri() }.getOrNull()
                ?: return@withContext null
            val base64 = MediaCache.uriToRawBase64(getApplication(), voiceUri) ?: return@withContext null
            base64 to (localMessage.parsedMeta().fileMimeType ?: "audio/mp4")
        }
        requireAiRequestCurrent(request)
        if (prepared == null) {
            failAiOperation(operationId, AiOperationError.CONTEXT_MISSING)
            _uiState.update {
                it.copy(
                    transcribingVoiceMessageIds = it.transcribingVoiceMessageIds - messageId,
                    groupEncryptionWarning = text(R.string.chat_voice_cache_missing)
                )
            }
            return@launchTrackedAiOperation
        }
        requireAiRequestCurrent(request)
        com.maodouchat.ai.agent.LocalAiGateway.transcribe(
            getApplication(),
            prepared.first,
            prepared.second
        ).fold(
            onSuccess = { rawTranscript ->
                requireAiRequestCurrent(request)
                val transcript = com.maodouchat.util.VoiceTranscriptPolicy.normalize(rawTranscript)
                if (transcript.isBlank()) {
                    failAiOperation(operationId, AiOperationError.EMPTY_RESULT)
                    _uiState.update {
                        it.copy(
                            transcribingVoiceMessageIds = it.transcribingVoiceMessageIds - messageId,
                            groupEncryptionWarning = text(R.string.chat_voice_transcript_empty)
                        )
                    }
                    return@fold
                }
                val current = _uiState.value.messages.firstOrNull { it.id == messageId } ?: message
                val updatedMeta = current.parsedMeta().copy(voiceTranscript = transcript)
                val updated = current.copy(
                    content = composeContentWithMeta(current.parsedContent(), updatedMeta),
                    meta = updatedMeta
                )
                if (!commitAiMessageResult(operationId, updated, request.userId, request.chatId)) return@fold
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.map { if (it.id == messageId) updated else it },
                        transcribingVoiceMessageIds = state.transcribingVoiceMessageIds - messageId,
                        groupEncryptionWarning = text(R.string.chat_voice_transcribed)
                    )
                }
            },
            onFailure = { error ->
                requireAiRequestCurrent(request)
                failAiOperation(operationId, aiOperationErrorCode(error))
                _uiState.update {
                    it.copy(
                        transcribingVoiceMessageIds = it.transcribingVoiceMessageIds - messageId,
                        groupEncryptionWarning = error.message ?: text(R.string.chat_voice_transcribe_failed)
                    )
                }
            }
        )
    }
}
