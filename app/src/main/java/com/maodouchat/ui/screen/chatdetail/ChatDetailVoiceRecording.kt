package com.maodouchat.ui.screen.chatdetail

import android.net.Uri
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.data.model.MessageType
import com.maodouchat.util.MediaCache
import com.maodouchat.util.VoiceCapturePolicy
import com.maodouchat.util.VoicePlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

// 语音录制与试听发送（自 ChatDetailViewModel.kt 拆分）。
// 录音、波形采样、试听确认、加密发送与取消流程集中于此，复用 viewModelScope 与 VoiceRecorder。

internal fun ChatDetailViewModel.startRecording() {
    val recordOwnerUserId = currentUserId
    if (
        token.isBlank() ||
        recordOwnerUserId.isBlank() ||
        recordOwnerUserId == "me" ||
        !com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = recordOwnerUserId,
            liveToken = tokenManager.getToken(),
            liveUserId = tokenManager.getUserId(),
        )
    ) {
        _uiState.update {
            it.copy(
                isRecording = false,
                recordingAmplitude = 0f,
                recordingElapsedMs = 0L,
                recordingWaveform = emptyList(),
                groupEncryptionWarning = text(R.string.error_session_expired)
            )
        }
        return
    }
    // 新录音前丢弃未发送试听，避免双文件
    discardVoicePreviewInternal(deleteFile = true)
    runCatching { voiceRecorder.startRecording() }
        .onSuccess {
            recordingWaveformBuffer.clear()
            _uiState.update {
                it.copy(
                    isRecording = true,
                    recordingAmplitude = 0f,
                    recordingElapsedMs = 0L,
                    recordingWaveform = emptyList(),
                    voicePreviewPath = null,
                    voicePreviewDurationMs = 0L,
                    groupEncryptionWarning = null,
                )
            }
            startRecordingMeter()
        }
        .onFailure { error ->
            Log.w("ChatDetailViewModel", "startRecording failed", error)
            stopRecordingMeter()
            _uiState.update {
                it.copy(
                    isRecording = false,
                    recordingAmplitude = 0f,
                    recordingElapsedMs = 0L,
                    recordingWaveform = emptyList(),
                    groupEncryptionWarning = text(R.string.chat_permission_record)
                )
            }
        }
}

internal fun ChatDetailViewModel.startRecordingMeter() {
    recordingMeterJob?.cancel()
    recordingMeterJob = viewModelScope.launch {
        while (isActive && voiceRecorder.isRecording) {
            val amp = voiceRecorder.amplitude()
            val elapsed = voiceRecorder.elapsedMs()
            recordingWaveformBuffer.push(amp)
            val snap = recordingWaveformBuffer.snapshot().toList()
            _uiState.update {
                it.copy(
                    recordingAmplitude = amp,
                    recordingElapsedMs = elapsed,
                    recordingWaveform = snap,
                )
            }
            delay(50)
        }
    }
}

internal fun ChatDetailViewModel.stopRecordingMeter() {
    recordingMeterJob?.cancel()
    recordingMeterJob = null
}

/**
 * 松手结束录音 → 进入发送前试听（不再直接发送）。
 * 过短则丢弃并提示。兼容旧调用名 [stopRecordingAndSend]。
 */
internal fun ChatDetailViewModel.stopRecordingAndSend() = stopRecordingToPreview()

internal fun ChatDetailViewModel.stopRecordingToPreview() {
    stopRecordingMeter()
    val result = voiceRecorder.stopRecording()
    _uiState.update {
        it.copy(
            isRecording = false,
            recordingAmplitude = 0f,
            recordingElapsedMs = 0L,
        )
    }
    if (result == null) {
        recordingWaveformBuffer.clear()
        _uiState.update { it.copy(recordingWaveform = emptyList()) }
        return
    }
    val (filePath, duration) = result
    val source = File(filePath)
    if (!VoiceCapturePolicy.canEnterPreview(duration) ||
        duration > MediaCache.MAX_VOICE_DURATION_MS
    ) {
        source.delete()
        recordingWaveformBuffer.clear()
        _uiState.update {
            it.copy(
                recordingWaveform = emptyList(),
                voicePreviewPath = null,
                voicePreviewDurationMs = 0L,
                groupEncryptionWarning = text(R.string.chat_voice_duration_invalid),
            )
        }
        return
    }
    _uiState.update {
        it.copy(
            voicePreviewPath = filePath,
            voicePreviewDurationMs = duration,
            groupEncryptionWarning = null,
        )
    }
}

internal fun ChatDetailViewModel.playVoicePreview() {
    val path = _uiState.value.voicePreviewPath ?: return
    VoicePlayer.ensureContext(getApplication())
    VoicePlayer.play(ChatDetailViewModel.VOICE_PREVIEW_MESSAGE_ID, path, getApplication())
}

internal fun ChatDetailViewModel.discardVoicePreview() {
    if (VoicePlayer.state.value.messageId == ChatDetailViewModel.VOICE_PREVIEW_MESSAGE_ID) {
        VoicePlayer.stop()
    }
    discardVoicePreviewInternal(deleteFile = true)
}

internal fun ChatDetailViewModel.discardVoicePreviewInternal(deleteFile: Boolean) {
    val path = _uiState.value.voicePreviewPath
    if (deleteFile && path != null) {
        runCatching { File(path).delete() }
    }
    recordingWaveformBuffer.clear()
    _uiState.update {
        it.copy(
            voicePreviewPath = null,
            voicePreviewDurationMs = 0L,
            recordingWaveform = emptyList(),
            recordingAmplitude = 0f,
            recordingElapsedMs = 0L,
        )
    }
}

/** 试听确认后走加密附件发送。 */
internal fun ChatDetailViewModel.sendVoicePreview() {
    if (!requireVoiceMessages()) return
    val path = _uiState.value.voicePreviewPath
    val duration = _uiState.value.voicePreviewDurationMs
    if (path.isNullOrBlank() || !VoiceCapturePolicy.canSendPreview(duration)) {
        discardVoicePreviewInternal(deleteFile = true)
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_voice_duration_invalid)) }
        return
    }
    if (VoicePlayer.state.value.messageId == ChatDetailViewModel.VOICE_PREVIEW_MESSAGE_ID) {
        VoicePlayer.stop()
    }
    val source = File(path)
    // 先清 UI 预览引用，文件由发送路径接管或删除
    _uiState.update {
        it.copy(voicePreviewPath = null, voicePreviewDurationMs = 0L)
    }
    val voiceOwnerUserId = currentUserId
    if (
        token.isBlank() ||
        voiceOwnerUserId.isBlank() ||
        voiceOwnerUserId == "me" ||
        !com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = voiceOwnerUserId,
            liveToken = tokenManager.getToken(),
            liveUserId = tokenManager.getUserId(),
        )
    ) {
        source.delete()
        _uiState.update {
            it.copy(isSending = false, groupEncryptionWarning = text(R.string.error_session_expired))
        }
        return
    }
    if (duration !in 500L..MediaCache.MAX_VOICE_DURATION_MS) {
        source.delete()
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_voice_duration_invalid)) }
        return
    }
    val messageId = "m_${UUID.randomUUID()}"
    val target = MediaCache.createPreparedAttachmentSource(getApplication(), messageId, ".m4a")
    _uiState.update { it.copy(isSending = true, groupEncryptionWarning = null) }
    viewModelScope.launch {
        try {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = voiceOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                withContext(NonCancellable) {
                    source.delete()
                    target.delete()
                }
                _uiState.update {
                    it.copy(isSending = false, groupEncryptionWarning = text(R.string.error_session_expired))
                }
                return@launch
            }
            val prepared = withContext(Dispatchers.IO) {
                try {
                    if (!source.renameTo(target)) {
                        source.copyTo(target, overwrite = true)
                        check(source.delete()) { "voice_source_cleanup_failed" }
                    }
                    target
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            }
            if (prepared == null) {
                withContext(Dispatchers.IO) {
                    source.delete()
                    target.delete()
                }
                _uiState.update {
                    it.copy(
                        isSending = false,
                        groupEncryptionWarning = text(R.string.chat_voice_cache_missing)
                    )
                }
                return@launch
            }
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = voiceOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                withContext(NonCancellable) {
                    prepared.delete()
                }
                _uiState.update {
                    it.copy(isSending = false, groupEncryptionWarning = text(R.string.error_session_expired))
                }
                return@launch
            }
            sendEncryptedAttachment(
                uri = Uri.fromFile(prepared),
                type = MessageType.VOICE,
                fixedMessageId = messageId,
                voiceDurationMs = duration
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) {
                source.delete()
                target.delete()
            }
            _uiState.update { it.copy(isSending = false) }
            throw error
        }
    }
}

internal fun ChatDetailViewModel.cancelRecording() {
    stopRecordingMeter()
    voiceRecorder.cancelRecording()
    recordingWaveformBuffer.clear()
    _uiState.update {
        it.copy(
            isRecording = false,
            recordingAmplitude = 0f,
            recordingElapsedMs = 0L,
            recordingWaveform = emptyList(),
        )
    }
}
