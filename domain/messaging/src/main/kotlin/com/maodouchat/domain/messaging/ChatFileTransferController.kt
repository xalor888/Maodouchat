package com.maodouchat.domain.messaging

import com.maodouchat.core.crypto.AttachmentErrorUiPolicy
import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.AttachmentErrorKind
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.RELIABLE_ATTACHMENT_TYPES
import com.maodouchat.domain.conversation.AttachmentTransferState
import com.maodouchat.domain.conversation.ChatMediaStateController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// 附件「下载 / 暂停 / 续传 / 取消 / 进度」控制器：MediaCache/Context/DAO/R 文案走端口，其余逻辑逐字平移。
class ChatFileTransferController(
    private val uiState: MutableStateFlow<ChatDetailUiState>,
    private val scope: CoroutineScope,
    private val applicationScope: CoroutineScope,
    private val activeChatId: () -> String,
    private val mediaStateController: ChatMediaStateController,
    private val attachmentIntentController: AttachmentIntentController,
    private val attachmentPreparationJobs: MutableMap<String, Job>,
    private val text: (FileTransferText) -> String,
    private val ensureLocalAttachment: suspend (Message) -> Result<Message>,
    /** 批量重试 / 批量取消走仓库动作，而不是把 app 单例交给控制器。 */
    private val retryAllTransfers: suspend (String) -> Unit,
    private val cancelAllTransfers: suspend (String) -> Unit,
    private val isReadableLocalUri: (String) -> Boolean,
    private val releasePersistableReadPermission: (String) -> Unit,
    private val deleteCachedMediaForMessage: (String) -> Unit,
    private val updateMessageStatus: suspend (String, MessageStatus) -> Unit,
    private val deleteMessage: suspend (String) -> Unit,
    private val currentUserId: () -> String,
    private val cleanupAttachmentTransfer: suspend (String, String) -> Unit,
    private val logWarning: (String, Throwable) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    fun requestOpenFile(messageId: String) {
        val message = uiState.value.messages.firstOrNull { it.id == messageId && it.type == MessageType.FILE } ?: return
        if (isReadableLocalUri(message.parsedContent())) {
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
                                failureMessage = attachmentErrorText(error, text(FileTransferText.DOWNLOAD_FAILED)),
                            )
                        }
                    },
                )
            } catch (error: CancellationException) {
                uiState.update { state -> mediaStateController.finishDownload(state, messageId) }
                throw error
            }
        }
    }
    fun requestMediaAttachment(messageId: String) {
        val message = uiState.value.messages.firstOrNull {
            it.id == messageId && it.type in RELIABLE_ATTACHMENT_TYPES
        } ?: return
        if (isReadableLocalUri(message.parsedContent())) return
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
                                failureMessage = attachmentErrorText(error, text(FileTransferText.DOWNLOAD_FAILED)),
                                markMediaFailure = true,
                            )
                        }
                    },
                )
            } catch (error: CancellationException) {
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
        scope.launch(ioDispatcher) {
            if (!attachmentIntentController.pause(messageId)) {
                uiState.update { it.copy(groupEncryptionWarning = text(FileTransferText.CONTROL_FAILED)) }
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
        scope.launch(ioDispatcher) {
            updateMessageStatus(messageId, MessageStatus.SENDING)
            if (!attachmentIntentController.resume(messageId)) {
                updateMessageStatus(messageId, MessageStatus.FAILED)
                uiState.update { state ->
                    state.copy(
                        messages = state.messages.map { if (it.id == message.id) it.copy(status = MessageStatus.FAILED) else it },
                        groupEncryptionWarning = text(FileTransferText.SOURCE_MISSING)
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
                sourceUri?.let { releasePersistableReadPermission(it) }
                deleteCachedMediaForMessage(messageId)
                deleteMessage(messageId)
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
        scope.launch(ioDispatcher) {
            attachmentIntentController.cancel(messageId)
            deleteCachedMediaForMessage(messageId)
            deleteMessage(messageId)
        }
    }
    fun retryAllAttachmentTransfers() {
        val chatId = activeChatId()
        if (chatId.isBlank()) return
        scope.launch(ioDispatcher) {
            retryAllTransfers(chatId)
        }
    }
    fun cancelAllAttachmentTransfers() {
        val chatId = activeChatId()
        if (chatId.isBlank()) return
        scope.launch(ioDispatcher) {
            cancelAllTransfers(chatId)
        }
    }
    fun updateFileTransferProgress(
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
    fun attachmentErrorText(error: Throwable, fallbackText: String): String =
        when (AttachmentErrorUiPolicy.classify(error)) {
            AttachmentErrorKind.TOO_LARGE ->
                text(FileTransferText.FILE_TOO_LARGE)
            AttachmentErrorKind.INVALID_REFERENCE ->
                text(FileTransferText.REFERENCE_INVALID)
            AttachmentErrorKind.INTEGRITY_FAILED ->
                text(FileTransferText.INTEGRITY_FAILED)
            AttachmentErrorKind.CONTENT_MISMATCH ->
                text(FileTransferText.CONTENT_MISMATCH)
            AttachmentErrorKind.FALLBACK -> {
                // 9.283：透传服务端具体错误（配额不足/总哈希校验失败/参数无效等），
                // 避免笼统的「附件发送失败」掩盖真实原因，便于用户自查与我方定位
                (error as? ApiException)?.serverMessage?.takeIf { it.isNotBlank() }
                    ?: fallbackText
            }
        }

    /** 删除消息时清理附件传输记录和本地密文文件，防止孤儿行和磁盘泄漏。 */
    suspend fun cleanupAttachmentForMessage(messageId: String) {
        try {
            val ownerUserId = currentUserId()
            if (ownerUserId.isBlank()) return
            cleanupAttachmentTransfer(messageId, ownerUserId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWarning("Attachment cleanup failed for $messageId", e)
        }
    }
}
