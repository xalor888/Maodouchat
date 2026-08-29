package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.local.entity.AttachmentTransferEntity
import com.maodouchat.data.local.entity.AttachmentTransferState
import com.maodouchat.data.model.MessageStatus

/** Owns transient attachment/download state transitions used by chat detail. */
internal class ChatMediaStateController {
    fun applyTransfers(
        state: ChatDetailUiState,
        transfers: List<AttachmentTransferEntity>,
    ): ChatDetailUiState {
        val byMessageId = transfers.associateBy { it.messageId }
        val progress = (state.fileTransferProgress - state.fileTransferStates.keys).toMutableMap()
        val states = mutableMapOf<String, String>()
        val errors = mutableMapOf<String, String>()
        transfers.forEach { transfer ->
            progress[transfer.messageId] = transfer.uiProgress()
            states[transfer.messageId] = transfer.state
            transfer.lastErrorCode?.let { errors[transfer.messageId] = it }
        }
        return state.copy(
            messages = state.messages.map { message ->
                when (byMessageId[message.id]?.state) {
                    AttachmentTransferState.FAILED -> message.copy(status = MessageStatus.FAILED)
                    AttachmentTransferState.QUEUED,
                    AttachmentTransferState.UPLOADING,
                    AttachmentTransferState.READY,
                    AttachmentTransferState.SENDING,
                    AttachmentTransferState.PAUSED -> message.copy(status = MessageStatus.SENDING)
                    else -> message
                }
            },
            fileTransferProgress = progress,
            fileTransferStates = states,
            fileTransferErrors = errors,
        )
    }

    fun beginDownload(state: ChatDetailUiState, messageId: String): ChatDetailUiState = state.copy(
        downloadingFileMessageIds = state.downloadingFileMessageIds + messageId,
        mediaDownloadErrorMessageIds = state.mediaDownloadErrorMessageIds - messageId,
        fileTransferProgress = state.fileTransferProgress + (messageId to 0f),
        groupEncryptionWarning = null,
    )

    fun finishDownload(
        state: ChatDetailUiState,
        messageId: String,
        localUri: String? = null,
        failureMessage: String? = null,
        markMediaFailure: Boolean = false,
    ): ChatDetailUiState = state.copy(
        downloadingFileMessageIds = state.downloadingFileMessageIds - messageId,
        mediaDownloadErrorMessageIds = if (markMediaFailure) {
            state.mediaDownloadErrorMessageIds + messageId
        } else {
            state.mediaDownloadErrorMessageIds - messageId
        },
        fileTransferProgress = state.fileTransferProgress - messageId,
        fileReadyToOpenUri = localUri ?: state.fileReadyToOpenUri,
        groupEncryptionWarning = failureMessage ?: state.groupEncryptionWarning,
    )

    fun consumeReadyFile(state: ChatDetailUiState): ChatDetailUiState =
        state.copy(fileReadyToOpenUri = null)

    fun updateSegmentProgress(
        state: ChatDetailUiState,
        messageId: String,
        completed: Long,
        total: Long,
        start: Float,
        end: Float,
    ): ChatDetailUiState {
        val progress = com.maodouchat.attachment.AttachmentProgressPolicy.mapSegmentProgress(
            completed = completed,
            total = total,
            start = start,
            end = end,
            previousPublished = state.fileTransferProgress[messageId] ?: -1f,
        ) ?: return state
        return state.copy(fileTransferProgress = state.fileTransferProgress + (messageId to progress))
    }

    private fun AttachmentTransferEntity.uiProgress(): Float = when (state) {
        AttachmentTransferState.QUEUED -> 0.35f
        AttachmentTransferState.UPLOADING -> 0.35f +
            (uploadedBytes.toDouble() / cipherSize.coerceAtLeast(1L)).coerceIn(0.0, 1.0).toFloat() * 0.55f
        AttachmentTransferState.READY, AttachmentTransferState.SENDING -> 0.92f
        AttachmentTransferState.PAUSED, AttachmentTransferState.FAILED ->
            0.35f + (uploadedBytes.toDouble() / cipherSize.coerceAtLeast(1L)).coerceIn(0.0, 1.0).toFloat() * 0.55f
        else -> 0f
    }
}
