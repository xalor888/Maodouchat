package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.util.DisappearingMessagePolicy
import com.maodouchat.util.RuntimeFlags
import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.ai.AiPrivacyPreferences
import com.maodouchat.ai.AiRetryPolicy
import com.maodouchat.attachment.AttachmentTransferCoordinator
import com.maodouchat.attachment.AttachmentTransferSummaryRepository
import com.maodouchat.attachment.AttachmentPreparationLease
import com.maodouchat.attachment.AttachmentSendCommand
import com.maodouchat.attachment.AttachmentSendWorkflow
import com.maodouchat.attachment.AttachmentSendWorkflowResult
import com.maodouchat.attachment.AttachmentDownloadCoordinator
import com.maodouchat.crypto.DecryptHistoryPolicy
import com.maodouchat.crypto.DecryptPlaceholderPolicy
import com.maodouchat.crypto.OwnSentMediaRestorePolicy
import com.maodouchat.crypto.SignalProtocol
import com.maodouchat.conversation.ConversationLocalCleanupMode
import com.maodouchat.conversation.ConversationLocalCleanupSession
import com.maodouchat.conversation.ConversationLocalCleanupStep
import com.maodouchat.conversation.conversationLocalCleanupSession
import com.maodouchat.conversation.createAndroidConversationLocalStateCoordinator
import com.maodouchat.messaging.v2.MessagingV2EventOutbox
import com.maodouchat.messaging.v2.MessagingV2MutationFacade
import com.maodouchat.messaging.v2.ConversationMessageMutationCoordinator
import com.maodouchat.messaging.v2.ConversationMessageMutationOutcome
import com.maodouchat.messaging.v2.ConversationReactionCoordinator
import com.maodouchat.messaging.v2.ConversationReactionOutcome
import com.maodouchat.messaging.v2.MessageMutationProjection
import com.maodouchat.messaging.v2.MessageMutationKind
import com.maodouchat.messaging.v2.createAndroidGroupMessagingCoordinator
import com.maodouchat.messaging.v2.MessagingV2MessageGateway
import com.maodouchat.messaging.v2.OutgoingConversationErrors
import com.maodouchat.messaging.v2.OutgoingConversationRequest
import com.maodouchat.messaging.v2.OutgoingMessageCommand
import com.maodouchat.messaging.v2.OutgoingMessageResult
import com.maodouchat.forwarding.ConversationForwardCoordinator
import com.maodouchat.data.local.entity.AttachmentTransferState
import com.maodouchat.data.local.entity.hasCompletedUpload
import com.maodouchat.data.local.entity.AiOperationEntity
import com.maodouchat.data.local.entity.AiOperationError
import com.maodouchat.data.local.entity.AiOperationParameters
import com.maodouchat.data.local.entity.AiOperationState
import com.maodouchat.data.local.entity.AiOperationType
import com.maodouchat.data.local.entity.AiTaskEntity
import com.maodouchat.data.local.entity.ChatDraftEntity
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.semanticSearchText
import com.maodouchat.data.model.User
import com.maodouchat.data.repository.ChatRepository
import com.maodouchat.data.repository.AiSummaryRepository
import com.maodouchat.data.repository.AiMessageResultStore
import com.maodouchat.data.repository.AiTaskRepository
import com.maodouchat.data.repository.AiOperationRepository
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.data.repository.UserRepository
import com.maodouchat.network.AiContextMessage
import com.maodouchat.network.AiGroupTask
import com.maodouchat.network.AiSemanticSearchCandidate
import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import com.maodouchat.network.ApiService
import com.maodouchat.network.ChatDto
import com.maodouchat.network.PinnedMessageDto
import com.maodouchat.network.TokenManager
import com.maodouchat.network.WebSocketEvent
import com.maodouchat.scheduling.AndroidConversationScheduleBackend
import com.maodouchat.scheduling.ChatReminderOutcome
import com.maodouchat.scheduling.ChatScheduleCommand
import com.maodouchat.scheduling.ChatScheduleController
import com.maodouchat.scheduling.ChatScheduleImmediateOutcome
import com.maodouchat.scheduling.ChatScheduleMutationOutcome
import com.maodouchat.scheduling.ChatScheduleRejection
import com.maodouchat.scheduling.ConversationScheduleCoordinator
import com.maodouchat.scheduling.MessageReminderRequest
import com.maodouchat.data.repository.ChatListPreviewPolicy
import com.maodouchat.ui.OwnerSessionPolicy
import com.maodouchat.ui.OwnerSessionSnapshot
import com.maodouchat.util.ImagePicker
import com.maodouchat.util.MediaCache
import com.maodouchat.util.AttachmentCryptoException
import com.maodouchat.util.AttachmentCryptoFailure
import com.maodouchat.util.VoiceCapturePolicy
import com.maodouchat.util.VoicePlayer
import com.maodouchat.util.VoiceRecorder
import com.maodouchat.util.VoiceRecordingWaveform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Calendar
import java.util.UUID


fun ChatDetailViewModel.loadForwardTargets() {
    viewModelScope.launch {
        try {
            val targets = withContext(Dispatchers.IO) {
                conversationForwardCoordinator.loadTargets(activeChatId)
            }
            _uiState.update { it.copy(forwardTargets = targets) }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            _uiState.update {
                it.copy(
                    groupEncryptionWarning = error.message
                        ?: text(R.string.chat_refresh_failed_cached),
                )
            }
        }
    }
}

fun ChatDetailViewModel.forwardMessage(message: Message, targetChatId: String) {
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.MESSAGE_FORWARDING)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.message_forwarding_disabled)) }
        return
    }
    if (_uiState.value.isSecretChat == true) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_chat_forward_blocked)) }
        return
    }
    val targetChat = _uiState.value.forwardTargets.firstOrNull { it.id == targetChatId } ?: return
    if (message.type !in ConversationForwardCoordinator.FORWARDABLE_TYPES) return
    if (_uiState.value.isForwarding) return
    viewModelScope.launch {
        _uiState.update { it.copy(isForwarding = true, groupEncryptionWarning = null) }
        val forwardResult = try {
            withContext(Dispatchers.IO) {
                conversationForwardCoordinator.forward(
                    target = targetChat,
                    message = message,
                    sourceName = forwardSourceName(message),
                )
            }
            Result.success(Unit)
        } catch (error: kotlinx.coroutines.CancellationException) {
            _uiState.update { it.copy(isForwarding = false) }
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
        _uiState.update {
            it.copy(
                isForwarding = false,
                groupEncryptionWarning = forwardResult.fold(
                    onSuccess = { text(R.string.chat_forwarded) },
                    onFailure = { error ->
                        if (message.type in RELIABLE_ATTACHMENT_TYPES) attachmentErrorText(error, R.string.chat_attachment_upload_failed)
                        else error.message
                    }
                )
            )
        }
    }
}

/**
 * 转发附带留言：向指定目标会话发送一条普通文本消息（1:1 Signal / 群 Sender Key）。
 * 写入 v2 持久发件箱；失败保留 SENDING 由进程级运行时重试。
 */
fun ChatDetailViewModel.sendTextToChat(targetChatId: String, text: String) {
    val trimmed = text.trim()
    if (trimmed.isBlank()) return
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.MESSAGE_FORWARDING)) return
    val targetChat = _uiState.value.forwardTargets.firstOrNull { it.id == targetChatId } ?: return
    viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) {
                conversationForwardCoordinator.sendNote(targetChat, trimmed)
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            // 失败保留 SENDING 待重试，不阻塞转发主流程
            Log.w("ChatDetailViewModel", "forward note send failed", error)
        }
    }
}

/**
 * 9.227：批量串行转发——修复多选转发（1.34）两个 bug：
 * 1. 旧实现对 N 条消息并行发 N 个协程，isForwarding 守卫失效（先完成者提前置 false），
 *    且群 Sender Key 分发与转发并发竞争、警告文案互相覆盖；
 * 2. 附带留言与附件转发并行立即发送，实际先于附件到达，与「转发完成后发送」语义相反。
 * 现按目标会话逐个串行转发全部消息（保持选择顺序），该会话无错时最后补发留言。
 */
fun ChatDetailViewModel.forwardMessagesBatch(messages: List<Message>, targetChatIds: List<String>, note: String?) {
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.MESSAGE_FORWARDING)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.message_forwarding_disabled)) }
        return
    }
    val usable = messages.filter {
        it.type in ConversationForwardCoordinator.FORWARDABLE_TYPES
    }
    if (usable.isEmpty() || targetChatIds.isEmpty()) return
    val isSecret = _uiState.value.isSecretChat == true
    if (isSecret) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_chat_forward_blocked)) }
        return
    }
    if (_uiState.value.isForwarding) return
    val targetsById = _uiState.value.forwardTargets.associateBy(Chat::id)
    val targets = targetChatIds.mapNotNull(targetsById::get)
    if (targets.isEmpty()) return
    viewModelScope.launch {
        _uiState.update { it.copy(isForwarding = true, groupEncryptionWarning = null) }
        val result = try {
            withContext(Dispatchers.IO) {
                conversationForwardCoordinator.forwardBatch(
                    targets = targets,
                    messages = usable,
                    note = note,
                    sourceName = ::forwardSourceName,
                )
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            _uiState.update { it.copy(isForwarding = false) }
            throw error
        } catch (error: Throwable) {
            com.maodouchat.forwarding.ForwardBatchResult(
                forwardedCount = 0,
                failedCount = usable.size * targets.size,
                firstError = error,
                attachmentFailed = usable.any { it.type in RELIABLE_ATTACHMENT_TYPES },
            )
        }
        _uiState.update {
            it.copy(
                isForwarding = false,
                groupEncryptionWarning = when {
                    result.firstError == null -> text(R.string.chat_forwarded)
                    result.attachmentFailed -> attachmentErrorText(
                        result.firstError,
                        R.string.chat_attachment_upload_failed,
                    )
                    else -> result.firstError.message
                },
            )
        }
    }
}

internal fun ChatDetailViewModel.forwardSourceName(message: Message): String? {
    if (_uiState.value.isSecretChat == true) return null
    return _uiState.value.chat
        ?.participants
        ?.firstOrNull { it.id == message.senderId }
        ?.name
        ?.takeIf(String::isNotBlank)
}

internal fun ChatDetailViewModel.forwardPreview(type: MessageType, content: String): String = when (type) {
    MessageType.IMAGE -> text(R.string.message_preview_image)
    MessageType.GIF -> text(R.string.message_preview_gif)
    MessageType.STICKER -> text(R.string.message_preview_sticker)
    MessageType.LOCATION -> text(R.string.message_preview_location)
    MessageType.VIDEO -> text(R.string.message_preview_video)
    MessageType.VOICE -> text(R.string.message_preview_voice)
    MessageType.FILE -> text(R.string.message_preview_file)
    else -> content.take(40)
}

/**
 * Durable attachment forward: same AttachmentTransfer outbox as normal send.
 * Process death after prepareAndEnqueue still uploads/finalizes via WorkManager.
 * (Previously: inline encrypt+upload+send with no local SENDING row.)
 */
internal suspend fun ChatDetailViewModel.forwardEncryptedAttachment(
    targetChat: Chat,
    message: Message,
    messageId: String,
    sourceName: String?,
    forwardOwnerUserId: String,
) {
    val localMessage = ensureLocalAttachment(message).getOrThrow()
    val localUri = Uri.parse(localMessage.parsedContent())
    val described = MediaCache.describeFile(getApplication(), localUri)
    val existingMeta = localMessage.parsedMeta()
    val metadata = MediaCache.LocalFileMetadata(
        fileName = existingMeta.fileName ?: described.fileName,
        mimeType = existingMeta.fileMimeType ?: described.mimeType,
        sizeBytes = described.sizeBytes.takeIf { it > 0L } ?: existingMeta.fileSizeBytes ?: 0L
    )
    val preparationLease = AttachmentPreparationLease(
        originalSourceUri = localUri.toString(),
        deleteEncryptedFile = { path -> runCatching { File(path).delete() } },
        deletePreparedSource = { source ->
            MediaCache.deletePreparedAttachmentSource(getApplication(), source)
        },
        releasePersistablePermission = { source ->
            MediaCache.releasePersistableReadPermission(getApplication(), source)
        }
    )
    if (forwardOwnerUserId.isBlank() || token.isBlank()) {
        throw IllegalStateException(text(R.string.error_session_expired))
    }
    try {
        val workflow = AttachmentSendWorkflow(
            context = getApplication(),
            messageStore = messageRepo,
            tokenManager = tokenManager,
            resolveChatId = { Result.success(targetChat.id) },
            onEncryptionProgress = { id, completed, total ->
                updateFileTransferProgress(id, completed, total, 0f, 0.35f)
            },
        )
        val result = workflow.execute(
            command = AttachmentSendCommand(
                messageId = messageId,
                sourceUri = localUri,
                type = message.type,
                chatId = targetChat.id,
                senderId = forwardOwnerUserId,
                voiceDurationMs = existingMeta.voiceDurationMs,
                forwardedFrom = existingMeta.forwardedFrom ?: sourceName,
                metadataOverride = metadata,
            ),
            lease = preparationLease,
            onOptimisticMessage = { optimistic ->
                if (targetChat.id == activeChatId) {
                    _uiState.update { st ->
                        st.copy(messages = mergeMessages(st.messages, listOf(optimistic)))
                    }
                }
            },
        )
        val queued = when (result) {
            is AttachmentSendWorkflowResult.Existing -> result.message
            is AttachmentSendWorkflowResult.Queued -> result.message
        }
        // Preview as soon as outbox is durable; finalizer refreshes after SENT.
        val preview = when (message.type) {
            MessageType.IMAGE -> text(R.string.message_preview_image)
            MessageType.GIF -> text(R.string.message_preview_gif)
            MessageType.VIDEO -> text(R.string.message_preview_video)
            MessageType.VOICE -> text(R.string.message_preview_voice)
            else -> text(R.string.message_preview_file)
        }
        com.maodouchat.MaodouchatApp.emitMessageSent(targetChat.id, preview, message.type.name)
    } catch (error: kotlinx.coroutines.CancellationException) {
        preparationLease.cleanupIfOwned()
        throw error
    } catch (error: Throwable) {
        val persisted = app.database.attachmentTransferDao().get(
            messageId,
            ownerUserId = forwardOwnerUserId
        ) != null
        if (persisted) {
            preparationLease.handOff()
        } else {
            preparationLease.cleanupIfOwned()
            val failed = (messageRepo.getMessageById(messageId)
                ?: Message(
                    id = messageId,
                    chatId = targetChat.id,
                    senderId = forwardOwnerUserId,
                    content = localUri.toString(),
                    type = message.type,
                    timestamp = System.currentTimeMillis(),
                )).copy(status = MessageStatus.FAILED)
            messageRepo.insertMessage(failed)
            if (targetChat.id == activeChatId) {
                _uiState.update { st ->
                    st.copy(messages = mergeMessages(st.messages, listOf(failed)))
                }
            }
        }
        throw error
    }
}
