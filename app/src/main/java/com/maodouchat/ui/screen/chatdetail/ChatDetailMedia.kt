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


fun ChatDetailViewModel.revealSpoilerMedia(messageId: String) {
    if (messageId.isBlank()) return
    val current = _uiState.value.messages.firstOrNull { it.id == messageId } ?: return
    val currentMeta = current.parsedMeta()
    if (!currentMeta.spoilerMedia || currentMeta.spoilerRevealed) return
    val revealed = current.withEncodedMeta(currentMeta.copy(spoilerRevealed = true))
    _uiState.update { state ->
        state.copy(
            messages = state.messages.map { msg ->
                if (msg.id != messageId) msg
                else {
                    val meta = msg.parsedMeta()
                    if (!meta.spoilerMedia || meta.spoilerRevealed) msg
                    else msg.withEncodedMeta(meta.copy(spoilerRevealed = true))
                }
            }
        )
    }
    persistLocalMediaMeta(revealed)
}

fun ChatDetailViewModel.markViewOnceOpened(messageId: String) {
    if (messageId.isBlank()) return
    val current = _uiState.value.messages.firstOrNull { it.id == messageId } ?: return
    val opened = com.maodouchat.util.ViewOncePolicy.markOpened(current)
    if (opened == current) return
    _uiState.update { state ->
        val updated = state.messages.map { msg ->
            if (msg.id != messageId) msg
            else com.maodouchat.util.ViewOncePolicy.markOpened(msg)
        }
        state.copy(messages = updated)
    }
    viewModelScope.launch(Dispatchers.IO) {
        val persisted = try {
            messageRepo.persistLocalMediaMeta(opened)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (!persisted) {
            Log.w("ChatDetailViewModel", "Failed to persist view-once opened state for $messageId")
        }
        // 9.147：删除媒体须与 ensureLocalAttachment 同锁串行——并发自动下载会把
        // 刚被擦除的阅后即焚媒体重新写回缓存（隐私失效），或在下载中途删出残缺文件
        attachmentDownloadCoordinator.withAttachmentLock(messageId) {
            runCatching {
                com.maodouchat.util.MediaCache.deleteCachedMediaForMessage(getApplication(), messageId)
            }
        }
    }
}

internal fun ChatDetailViewModel.persistLocalMediaMeta(message: Message) {
    viewModelScope.launch(Dispatchers.IO) {
        val ok = try {
            messageRepo.persistLocalMediaMeta(message)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            Log.w("ChatDetailViewModel", "Failed to persist local media metadata for ${message.id}")
        }
    }
}

fun ChatDetailViewModel.sendImage(uri: Uri) {
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.IMAGE_SEND)) {
        _uiState.update { it.copy(errorMessage = text(R.string.image_send_disabled)) }
        return
    }
    sendEncryptedAttachment(uri, MessageType.IMAGE)
}
fun ChatDetailViewModel.sendViewOnceImage(uri: Uri) {
    if (_uiState.value.chatIsGroup) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.view_once_direct_only)) }
        return
    }
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.VIEW_ONCE)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.view_once_disabled)) }
        return
    }
    sendEncryptedAttachment(uri, MessageType.IMAGE, viewOnce = true)
}
fun ChatDetailViewModel.sendViewOnceVideo(uri: Uri) {
    if (_uiState.value.chatIsGroup) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.view_once_direct_only)) }
        return
    }
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.VIEW_ONCE)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.view_once_disabled)) }
        return
    }
    sendEncryptedAttachment(uri, MessageType.VIDEO, viewOnce = true)
}
fun ChatDetailViewModel.sendSpoilerImage(uri: Uri) {
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.SPOILER_MEDIA)) {
        _uiState.update { it.copy(errorMessage = text(R.string.spoiler_media_disabled)) }
        return
    }
    sendEncryptedAttachment(uri, MessageType.IMAGE, spoilerMedia = true)
}
fun ChatDetailViewModel.sendSpoilerVideo(uri: Uri) {
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.SPOILER_MEDIA)) {
        _uiState.update { it.copy(errorMessage = text(R.string.spoiler_media_disabled)) }
        return
    }
    sendEncryptedAttachment(uri, MessageType.VIDEO, spoilerMedia = true)
}
fun ChatDetailViewModel.sendGif(uri: Uri) {
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.GIF_SEND)) {
        _uiState.update { it.copy(errorMessage = text(R.string.gif_send_disabled)) }
        return
    }
    sendEncryptedAttachment(uri, MessageType.GIF)
}
fun ChatDetailViewModel.sendVideo(uri: Uri) {
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.VIDEO_SEND)) {
        _uiState.update { it.copy(errorMessage = text(R.string.video_send_disabled)) }
        return
    }
    sendEncryptedAttachment(uri, MessageType.VIDEO)
}
fun ChatDetailViewModel.sendFile(uri: Uri) {
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.FILE_SHARE)) {
        _uiState.update { it.copy(errorMessage = text(R.string.file_share_disabled)) }
        return
    }
    sendEncryptedAttachment(uri, MessageType.FILE)
}

fun ChatDetailViewModel.sendSticker(sticker: String) {
    if (!requireStickers()) return
    val content = sticker.trim().take(32)
    if (content.isBlank()) return
    // 最近使用按账号本地记录，与发送解耦
    runCatching {
        com.maodouchat.util.StickerPreferences.recordRecent(getApplication(), content)
    }
    sendInlineContent(content, MessageType.STICKER, text(R.string.message_preview_sticker))
}
