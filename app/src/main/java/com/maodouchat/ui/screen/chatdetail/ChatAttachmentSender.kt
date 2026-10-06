package com.maodouchat.ui.screen.chatdetail

import android.net.Uri
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.domain.messaging.AttachmentIntent
import com.maodouchat.domain.messaging.AttachmentIntentController
import com.maodouchat.domain.messaging.AttachmentKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal class ChatAttachmentSender(
    private val scope: CoroutineScope,
    private val activeChatId: () -> String,
    private val ownerUserId: () -> String,
    private val token: () -> String,
    private val mediaUploadEnabled: () -> Boolean,
    private val currentState: () -> ChatDetailUiState,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val mergeMessages: (List<Message>, List<Message>) -> List<Message>,
    private val attachmentIntentController: AttachmentIntentController,
    private val getMessageById: suspend (String) -> Message?,
    private val persistMessage: suspend (Message) -> Unit,
    private val resumeFileTransfer: (String) -> Unit,
    private val preparationJobs: MutableMap<String, Job>,
    private val currentSessionUserId: () -> String,
    private val attachmentErrorText: (Throwable, Int) -> String,
    private val text: (Int, Array<out Any>) -> String,
) {
    /**
     * 加密发送附件（图片/视频/语音/文件/GIF）。
     * 参数语义与原 `ChatDetailViewModel.sendEncryptedAttachment` 完全一致
     * （默认值保留在 VM 侧的委托签名上）。
     */
    fun sendEncryptedAttachment(
        uri: Uri,
        type: MessageType,
        fixedMessageId: String?,
        existingMessage: Message?,
        voiceDurationMs: Long?,
        viewOnce: Boolean,
        spoilerMedia: Boolean,
    ) {
        if (currentState().isSending && fixedMessageId == null) return
        if (!mediaUploadEnabled()) {
            updateState { it.copy(groupEncryptionWarning = string(R.string.media_upload_disabled)) }
            return
        }

        require(type in RELIABLE_ATTACHMENT_TYPES)
        val attachOwnerUserId = ownerUserId()
        if (token().isBlank() || attachOwnerUserId.isBlank()) {
            updateState {
                it.copy(isSending = false, groupEncryptionWarning = string(R.string.error_session_expired))
            }
            return
        }
        val messageId = fixedMessageId ?: "m_${UUID.randomUUID()}"
        val kind = when (type) {
            MessageType.IMAGE -> AttachmentKind.IMAGE
            MessageType.VIDEO -> AttachmentKind.VIDEO
            MessageType.VOICE -> AttachmentKind.VOICE
            MessageType.FILE -> AttachmentKind.FILE
            MessageType.GIF -> AttachmentKind.GIF
            else -> AttachmentKind.FILE
        }
        val isGroup = currentState().chat?.isGroup == true
        val intent = AttachmentIntent(
            conversationId = activeChatId(),
            kind = kind,
            uri = uri.toString(),
            idempotencyKey = messageId,
            durationMs = voiceDurationMs,
            viewOnce = viewOnce && !isGroup,
            spoilerMedia = spoilerMedia
        )

        val optimistic = existingMessage?.copy(
            chatId = activeChatId(),
            content = uri.toString(),
            status = MessageStatus.SENDING,
            meta = MessageMeta(
                voiceDurationMs = voiceDurationMs,
                viewOnce = viewOnce && !isGroup,
                spoilerMedia = spoilerMedia
            )
        ) ?: Message(
            id = messageId,
            chatId = activeChatId(),
            senderId = attachOwnerUserId,
            content = uri.toString(),
            type = type,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENDING,
            meta = MessageMeta(
                voiceDurationMs = voiceDurationMs,
                viewOnce = viewOnce && !isGroup,
                spoilerMedia = spoilerMedia
            )
        )

        updateState {
            it.copy(
                messages = mergeMessageVersions(it.messages, listOf(optimistic)),
                isSending = true,
                fileTransferProgress = it.fileTransferProgress + (messageId to 0f),
                preparingAttachmentMessageIds = it.preparingAttachmentMessageIds + messageId,
                groupEncryptionWarning = null,
            )
        }

        val preparationJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val result = attachmentIntentController.submit(intent)
            result.fold(
                onSuccess = {
                    val queued = withContext(Dispatchers.IO) { getMessageById(messageId) }
                    updateState { state ->
                        state.copy(
                            messages = if (queued != null) {
                                state.messages.map { current -> if (current.id == messageId) queued else current }
                            } else state.messages,
                            isSending = false,
                            preparingAttachmentMessageIds = state.preparingAttachmentMessageIds - messageId,
                        )
                    }
                    if (existingMessage != null) {
                        resumeFileTransfer(messageId)
                    }
                },
                onFailure = { error ->
                    if (error is kotlinx.coroutines.CancellationException) {
                        updateState {
                            it.copy(
                                isSending = false,
                                preparingAttachmentMessageIds = it.preparingAttachmentMessageIds - messageId
                            )
                        }
                        throw error
                    }
                    android.util.Log.w("ChatDetailViewModel", "Attachment preparation failed: $messageId", error)
                    val failed = (withContext(Dispatchers.IO) { getMessageById(messageId) }
                        ?: existingMessage
                        ?: optimistic).copy(status = MessageStatus.FAILED)
                    updateState {
                        it.copy(
                            messages = it.messages.map { current -> if (current.id == messageId) failed else current },
                            isSending = false,
                            fileTransferProgress = it.fileTransferProgress - messageId,
                            preparingAttachmentMessageIds = it.preparingAttachmentMessageIds - messageId,
                            groupEncryptionWarning = attachmentErrorText(error, R.string.chat_attachment_upload_failed)
                        )
                    }
                    withContext(Dispatchers.IO) { persistMessage(failed) }
                }
            )
        }
        preparationJobs[messageId] = preparationJob
        preparationJob.invokeOnCompletion { error ->
            preparationJobs.remove(messageId, preparationJob)
            if (error != null) {
                if (currentSessionUserId() != attachOwnerUserId) return@invokeOnCompletion
                updateState {
                    it.copy(
                        isSending = false,
                        preparingAttachmentMessageIds = it.preparingAttachmentMessageIds - messageId
                    )
                }
            }
        }
        preparationJob.start()
    }

    private fun string(resourceId: Int, vararg args: Any): String = text(resourceId, args)
}
