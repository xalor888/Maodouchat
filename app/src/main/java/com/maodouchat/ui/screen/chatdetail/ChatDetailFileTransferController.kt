package com.maodouchat.ui.screen.chatdetail

import android.content.Context
import com.maodouchat.R
import com.maodouchat.data.local.entity.AttachmentTransferState
import com.maodouchat.domain.messaging.AttachmentIntentController
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.util.MediaCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 附件「下载 / 暂停 / 续传 / 取消 / 进度」这一族的控制逻辑（G328c 从
 * `ChatDetailViewModel` 抽出，纯搬移不改判断）。
 *
 * 为什么先抽它：这 10 个方法在 ViewModel 里是 179 行**薄编排**——真正干活的
 * 是 `attachmentIntentController` / `mediaStateController` /
 * `attachmentDownloadCoordinator`，而 ViewModel 只负责把它们与 `uiState` 缝起来。
 * 缝线属于「附件传输」这一个关注点，独立成类之后，ViewModel 里剩下的
 * 就是转发（一行一个）而不是实现。
 *
 * 依赖全部显式传入（含 `scope` / `context` / `text`），因此它**不依赖 ViewModel**；
 * 但也如实说明：本类仍会触碰 MediaCache 与 Android Context，所以它属于
 * 「可读性/职责」上的拆分，不是「可单测」上的拆分——判定类逻辑在
 * `ChatDetailDecryptStatus` 那种纯类里才谈得上单测。
 */
internal class ChatDetailFileTransferController(
    private val uiState: MutableStateFlow<ChatDetailUiState>,
    private val scope: CoroutineScope,
    private val applicationScope: CoroutineScope,
    private val context: Context,
    private val activeChatId: () -> String,
    private val messageRepo: LocalMessageStore,
    private val mediaStateController: ChatMediaStateController,
    private val attachmentIntentController: AttachmentIntentController,
    private val attachmentPreparationJobs: MutableMap<String, Job>,
    private val text: (Int) -> String,
    private val ensureLocalAttachment: suspend (Message) -> Result<Message>,
    /** 批量重试 / 批量取消走仓库动作，而不是把 app 单例交给控制器。 */
    private val retryAllTransfers: suspend (String) -> Unit,
    private val cancelAllTransfers: suspend (String) -> Unit,
) {

    fun requestOpenFile(messageId: String) {
        val message = uiState.value.messages.firstOrNull { it.id == messageId && it.type == MessageType.FILE } ?: return
        if (MediaCache.isReadableLocalUri(context, message.parsedContent())) {
            uiState.update { it.copy(fileReadyToOpenUri = message.parsedContent()) }
            return
        }
        if (messageId in uiState.value.downloadingFileMessageIds) return
        scope.launch {
            uiState.update { state -> mediaStateController.beginDownload(state, messageId) }
            try {
                ensureLocalAttachment(message).fold(
                    onSuccess = { localMessage ->
                        uiState.update { state ->
                            mediaStateController.finishDownload(
                                state,
                                messageId,
                                localUri = localMessage.parsedContent(),
                            )
                        }
                    },
                    onFailure = { error ->
                        uiState.update { state ->
                            mediaStateController.finishDownload(
                                state,
                                messageId,
                                failureMessage = attachmentErrorText(error, R.string.chat_attachment_download_failed),
                            )
                        }
                    },
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                uiState.update { state -> mediaStateController.finishDownload(state, messageId) }
                throw error
            }
        }
    }
    fun requestMediaAttachment(messageId: String) {
        val message = uiState.value.messages.firstOrNull {
            it.id == messageId && it.type in RELIABLE_ATTACHMENT_TYPES
        } ?: return
        if (MediaCache.isReadableLocalUri(context, message.parsedContent())) return
        if (messageId in uiState.value.downloadingFileMessageIds) return
        scope.launch {
            uiState.update { state -> mediaStateController.beginDownload(state, messageId) }
            try {
                ensureLocalAttachment(message).fold(
                    onSuccess = {
                        uiState.update { state -> mediaStateController.finishDownload(state, messageId) }
                    },
                    onFailure = { error ->
                        uiState.update { state ->
                            mediaStateController.finishDownload(
                                state,
                                messageId,
                                failureMessage = attachmentErrorText(error, R.string.chat_attachment_download_failed),
                                markMediaFailure = true,
                            )
                        }
                    },
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                uiState.update { state -> mediaStateController.finishDownload(state, messageId) }
                throw error
            }
        }
    }
    fun consumeFileReadyToOpen() {
        uiState.update(mediaStateController::consumeReadyFile)
    }
    fun pauseFileTransfer(messageId: String) {
        if (uiState.value.fileTransferStates[messageId] !in setOf(AttachmentTransferState.QUEUED, AttachmentTransferState.UPLOADING)) return
        scope.launch(Dispatchers.IO) {
            if (!attachmentIntentController.pause(messageId)) {
                uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_file_transfer_control_failed)) }
            }
        }
    }
    fun resumeFileTransfer(messageId: String) {
        val message = uiState.value.messages.firstOrNull {
            it.id == messageId && it.type in RELIABLE_ATTACHMENT_TYPES
        } ?: return
        uiState.update { state ->
            state.copy(
                messages = state.messages.map { if (it.id == messageId) it.copy(status = MessageStatus.SENDING) else it },
                fileTransferErrors = state.fileTransferErrors - messageId,
                groupEncryptionWarning = null
            )
        }
        scope.launch(Dispatchers.IO) {
            messageRepo.updateMessageStatus(messageId, MessageStatus.SENDING)
            if (!attachmentIntentController.resume(messageId)) {
                messageRepo.updateMessageStatus(messageId, MessageStatus.FAILED)
                uiState.update { state ->
                    state.copy(
                        messages = state.messages.map { if (it.id == message.id) it.copy(status = MessageStatus.FAILED) else it },
                        groupEncryptionWarning = text(R.string.chat_file_transfer_source_missing)
                    )
                }
            }
        }
    }
    fun cancelFileTransfer(messageId: String) {
        if (messageId in uiState.value.preparingAttachmentMessageIds) {
            val sourceUri = uiState.value.messages.firstOrNull { it.id == messageId }?.parsedContent()
            attachmentPreparationJobs[messageId]?.cancel()
            uiState.update { state ->
                state.copy(
                    messages = state.messages.filterNot { it.id == messageId },
                    fileTransferProgress = state.fileTransferProgress - messageId,
                    preparingAttachmentMessageIds = state.preparingAttachmentMessageIds - messageId,
                    isSending = false
                )
            }
            applicationScope.launch {
                attachmentIntentController.cancel(messageId)
                sourceUri?.let { MediaCache.releasePersistableReadPermission(context, it) }
                MediaCache.deleteCachedMediaForMessage(context, messageId)
                messageRepo.deleteMessage(messageId)
            }
            return
        }
        val transferState = uiState.value.fileTransferStates[messageId] ?: return
        if (transferState in setOf(AttachmentTransferState.READY, AttachmentTransferState.SENDING)) return
        uiState.update { state ->
            state.copy(
                messages = state.messages.filterNot { it.id == messageId },
                fileTransferProgress = state.fileTransferProgress - messageId,
                fileTransferStates = state.fileTransferStates - messageId,
                fileTransferErrors = state.fileTransferErrors - messageId
            )
        }
        scope.launch(Dispatchers.IO) {
            attachmentIntentController.cancel(messageId)
            MediaCache.deleteCachedMediaForMessage(context, messageId)
            messageRepo.deleteMessage(messageId)
        }
    }
    fun retryAllAttachmentTransfers() {
        val chatId = activeChatId()
        if (chatId.isBlank()) return
        scope.launch(Dispatchers.IO) {
            retryAllTransfers(chatId)
        }
    }
    fun cancelAllAttachmentTransfers() {
        val chatId = activeChatId()
        if (chatId.isBlank()) return
        scope.launch(Dispatchers.IO) {
            cancelAllTransfers(chatId)
        }
    }
    internal fun updateFileTransferProgress(
        messageId: String,
        completed: Long,
        total: Long,
        start: Float,
        end: Float
    ) {
        uiState.update { state ->
            mediaStateController.updateSegmentProgress(state, messageId, completed, total, start, end)
        }
    }
    internal fun attachmentErrorText(error: Throwable, fallbackStringRes: Int): String =
        when (com.maodouchat.attachment.AttachmentErrorUiPolicy.classify(error)) {
            com.maodouchat.attachment.AttachmentErrorUiPolicy.Kind.TOO_LARGE ->
                text(R.string.chat_attachment_file_too_large)
            com.maodouchat.attachment.AttachmentErrorUiPolicy.Kind.INVALID_REFERENCE ->
                text(R.string.chat_attachment_reference_invalid)
            com.maodouchat.attachment.AttachmentErrorUiPolicy.Kind.INTEGRITY_FAILED ->
                text(R.string.chat_attachment_integrity_failed)
            com.maodouchat.attachment.AttachmentErrorUiPolicy.Kind.CONTENT_MISMATCH ->
                text(R.string.chat_attachment_content_mismatch)
            com.maodouchat.attachment.AttachmentErrorUiPolicy.Kind.FALLBACK -> {
                // 9.283：透传服务端具体错误（配额不足/总哈希校验失败/参数无效等），
                // 避免笼统的「附件发送失败」掩盖真实原因，便于用户自查与我方定位
                (error as? com.maodouchat.network.ApiException)?.serverMessage?.takeIf { it.isNotBlank() }
                    ?: text(fallbackStringRes)
            }
        }}
