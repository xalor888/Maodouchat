package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.DisappearingMessagePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// onCleared 收尾一族：从 ChatDetailViewModel 纯搬移；VM 只留 override 转发。
// Application/CurrentSession/会话门禁/VoiceRecorder/VoicePlayer/Log 走端口，其余逻辑逐字平移。
class ChatSessionTeardownController(
    private val uiState: MutableStateFlow<ChatDetailUiState>,
    private val scope: CoroutineScope,
    private val draftsFeatureEnabled: () -> Boolean,
    private val cancelRecording: () -> Unit,
    private val stopVoicePlayback: () -> Unit,
    private val clearRealtime: () -> Unit,
    private val cipherController: ChatSessionCipherController,
    private val getActiveChatId: () -> String,
    private val stopLiveLocationSharing: (Boolean) -> Unit,
    private val stopRecordingMeter: () -> Unit,
    private val cancelAiAutoRetryJobs: () -> Unit,
    private val cancelDraftSaveJob: () -> Unit,
    private val clearReadSeen: () -> Unit,
    private val takePendingReadWatermark: () -> String?,
    private val cancelMarkReadJob: () -> Unit,
    private val persistDraft: suspend (String, String, String) -> Unit,
    private val ownerUserId: () -> String,
    private val sessionMayContinue: (String) -> Boolean,
    private val sessionToken: () -> String?,
    private val sessionUserId: () -> String?,
    private val enqueueReadReceipt: suspend (String, String, Long?) -> Unit,
    private val logWarning: (String, Throwable?) -> Unit,
) {
    fun onCleared() {
        stopMediaAndJobs()
        persistDraftOnClear()
        enqueueFinalReadReceipt()
    }

    private fun stopMediaAndJobs() {
        // 8.45：离开会话仍向对端发送实时位置终态（内部经 applicationScope 发送，
        // 不依赖即将取消的 viewModelScope），避免对端残留 live 位置标记
        stopLiveLocationSharing(true)
        // 离开聊天页必须释放麦克风与未完成的语音临时文件，避免幽灵录音占用 MIC
        stopRecordingMeter()
        runCatching { cancelRecording() }
        val previewPath = uiState.value.voicePreviewPath
        if (previewPath != null) {
            runCatching { File(previewPath).delete() }
        }
        // 全局 VoicePlayer 不随 ViewModel 销毁；离开会话应停播，避免跨页串音
        runCatching { stopVoicePlayback() }
        cancelAiAutoRetryJobs()
        cancelDraftSaveJob()
        clearRealtime()
    }

    private fun persistDraftOnClear() {
        val draftOwnerUserId = ownerUserId()
        val draftChatId = getActiveChatId()
        val draftText = uiState.value.inputText
        // 8.49 修复：与 scheduleDraftPersistence/clearDraft/restoreDraft 一致检查 CHAT_DRAFTS
        // 运行时开关——管理员关闭草稿功能后，残余输入不应被持久化为明文草稿
        val draftsEnabled = draftsFeatureEnabled()
        // 捕获 persistDraft 到局部变量，避免 lambda 闭包捕获 controller（经 host lambda
        // 间接引用 ViewModel），从而防止 ViewModel 被 applicationScope 中的挂起引用阻止 GC 回收。
        val doPersist = persistDraft
        if (draftsEnabled && draftOwnerUserId.isNotBlank() && draftChatId.isNotBlank()) {
            scope.launch {
                withContext(NonCancellable) {
                    // Soft-purge/logout may destroy Room or switch owner before this runs.
                    if (!sessionMayContinue(draftOwnerUserId)
                    ) {
                        return@withContext
                    }
                    doPersist(draftOwnerUserId, draftChatId, draftText)
                }
            }
        }
    }

    private fun enqueueFinalReadReceipt() {
        val finalReadWatermark = takePendingReadWatermark()
        clearReadSeen()
        cancelMarkReadJob()
        val currentChatId = getActiveChatId()
        val readOwnerUserId = ownerUserId()
        val readIsSecret = uiState.value.isSecretChat == true
        val readGroupRevision = uiState.value.chat?.memberRevision
            ?.takeIf { uiState.value.chat?.isGroup == true }
        cipherController.releaseSessionCipher()
        if (currentChatId.isNotBlank() && readOwnerUserId.isNotBlank() && finalReadWatermark != null) {
            scope.launch {
                try {
                    withContext(NonCancellable) {
                        val liveToken = sessionToken()
                        val liveUserId = sessionUserId()
                        if (liveToken.isNullOrBlank() || liveUserId != readOwnerUserId) return@withContext
                        if (!DisappearingMessagePolicy.shouldSkipReadReceipts(readIsSecret)) {
                            // 函数类型端口不支持具名实参，这里一律用位置实参。
                            enqueueReadReceipt(
                                currentChatId,
                                finalReadWatermark,
                                readGroupRevision,
                            )
                        }
                    }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    logWarning("final v2 read receipt enqueue failed", error)
                }
            }
        }
    }
}
