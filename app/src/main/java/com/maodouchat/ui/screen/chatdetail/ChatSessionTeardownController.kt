package com.maodouchat.ui.screen.chatdetail

import android.app.Application
import com.maodouchat.chatdetail.ChatDetailAccess
import com.maodouchat.util.DisappearingMessagePolicy
import com.maodouchat.util.RuntimeFlags
import com.maodouchat.util.VoicePlayer
import com.maodouchat.util.VoiceRecorder
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// onCleared 收尾一族：从 ChatDetailViewModel 纯搬移；VM 只留 override 转发。
internal class ChatSessionTeardownController(
    private val uiState: MutableStateFlow<ChatDetailUiState>,
    private val application: Application,
    private val voiceRecorder: VoiceRecorder,
    private val realtimeController: ChatRealtimeController,
    private val cipherController: ChatSessionCipherController,
    private val getActiveChatId: () -> String,
    private val stopLiveLocationSharing: (Boolean) -> Unit,
    private val stopRecordingMeter: () -> Unit,
    private val cancelAiAutoRetryJobs: () -> Unit,
    private val cancelDraftSaveJob: () -> Unit,
    private val clearReadSeen: () -> Unit,
    private val takePendingReadWatermark: () -> String?,
    private val cancelMarkReadJob: () -> Unit,
    private val persistDraft: suspend (ownerUserId: String, chatId: String, text: String) -> Unit,
) {
    internal fun onCleared() {
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
        runCatching { voiceRecorder.cancelRecording() }
        val previewPath = uiState.value.voicePreviewPath
        if (previewPath != null) {
            runCatching { File(previewPath).delete() }
        }
        // 全局 VoicePlayer 不随 ViewModel 销毁；离开会话应停播，避免跨页串音
        runCatching { VoicePlayer.stop() }
        cancelAiAutoRetryJobs()
        cancelDraftSaveJob()
        realtimeController.clear()
    }

    private fun persistDraftOnClear() {
        val draftOwnerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        val draftChatId = getActiveChatId()
        val draftText = uiState.value.inputText
        // 8.49 修复：与 scheduleDraftPersistence/clearDraft/restoreDraft 一致检查 CHAT_DRAFTS
        // 运行时开关——管理员关闭草稿功能后，残余输入不应被持久化为明文草稿
        val draftsFeatureEnabled = RuntimeFlags.isEnabled(application, RuntimeFlags.CHAT_DRAFTS)
        // 捕获 persistDraft 到局部变量，避免 lambda 闭包捕获 controller（经 host lambda
        // 间接引用 ViewModel），从而防止 ViewModel 被 applicationScope 中的挂起引用阻止 GC 回收。
        val doPersist = persistDraft
        if (draftsFeatureEnabled && draftOwnerUserId.isNotBlank() && draftChatId.isNotBlank()) {
            ChatDetailAccess.applicationScope.launch {
                withContext(NonCancellable) {
                    // Soft-purge/logout may destroy Room or switch owner before this runs.
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(draftOwnerUserId)
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
        val readOwnerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        val readIsSecret = uiState.value.isSecretChat == true
        val readGroupRevision = uiState.value.chat?.memberRevision
            ?.takeIf { uiState.value.chat?.isGroup == true }
        val messagingOutbox = ChatDetailAccess.messagingOutbox
        cipherController.releaseSessionCipher()
        if (currentChatId.isNotBlank() && readOwnerUserId.isNotBlank() && finalReadWatermark != null) {
            ChatDetailAccess.applicationScope.launch {
                try {
                    withContext(NonCancellable) {
                        val liveToken = com.maodouchat.session.CurrentSession.snapshot().token
                        val liveUserId = com.maodouchat.session.CurrentSession.snapshot().userId
                        if (liveToken.isNullOrBlank() || liveUserId != readOwnerUserId) return@withContext
                        if (!DisappearingMessagePolicy.shouldSkipReadReceipts(readIsSecret)) {
                            messagingOutbox.enqueueReadReceipt(
                                conversationId = currentChatId,
                                throughMessageId = finalReadWatermark,
                                groupRevision = readGroupRevision,
                            )
                        }
                    }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    android.util.Log.w("ChatDetailViewModel", "final v2 read receipt enqueue failed", error)
                }
            }
        }
    }
}
