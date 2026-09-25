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


fun ChatDetailViewModel.sendLiveLocation(durationMs: Long = com.maodouchat.util.LiveLocationPolicy.DURATION_OPTIONS_MS.first()) {
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.LIVE_LOCATION)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.live_location_disabled)) }
        return
    }
    viewModelScope.launch {
        val locOwnerUserId = currentUserId
        if (token.isBlank() || locOwnerUserId.isBlank()) {
            _uiState.update {
                it.copy(isSending = false, groupEncryptionWarning = text(R.string.error_session_expired))
            }
            return@launch
        }
        _uiState.update { it.copy(isSending = true, groupEncryptionWarning = null) }
        try {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = locOwnerUserId,
            )
            ) {
                _uiState.update { it.copy(isSending = false, groupEncryptionWarning = text(R.string.error_session_expired)) }
                return@launch
            }
            com.maodouchat.util.LocationProvider.currentLocation(getApplication()).fold(
                onSuccess = { location ->
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = locOwnerUserId,
                    )
                    ) {
                        _uiState.update { it.copy(isSending = false, groupEncryptionWarning = text(R.string.error_session_expired)) }
                        return@fold
                    }
                    val now = System.currentTimeMillis()
                    val sessionId = "live_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
                    val until = now + durationMs.coerceIn(
                        com.maodouchat.util.LiveLocationPolicy.DURATION_OPTIONS_MS.first(),
                        com.maodouchat.util.LiveLocationPolicy.DURATION_OPTIONS_MS.last()
                    )
                    val payload = com.maodouchat.data.model.LocationPayload(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
                        label = text(R.string.chat_live_location_send),
                        capturedAt = location.time.takeIf { it > 0 } ?: now,
                        live = true,
                        liveUntil = until,
                        sessionId = sessionId
                    )
                    val content = kotlinx.serialization.json.Json.encodeToString(
                        com.maodouchat.data.model.LocationPayload.serializer(),
                        payload
                    )
                    val messageId = sendInlineContent(
                        content,
                        MessageType.LOCATION,
                        text(R.string.message_preview_live_location)
                    ) ?: return@fold
                    lastLiveLocationPayload = payload
                    _uiState.update {
                        it.copy(
                            activeLiveLocationMessageId = messageId,
                            activeLiveLocationSessionId = sessionId,
                            activeLiveLocationUntil = until
                        )
                    }
                    startLiveLocationUpdates(messageId, sessionId, until)
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            groupEncryptionWarning = err.message?.take(120)
                                ?: text(R.string.message_location_unavailable)
                        )
                    }
                }
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update {
                it.copy(isSending = false, groupEncryptionWarning = e.message?.take(120))
            }
        }
    }
}

fun ChatDetailViewModel.stopLiveLocationSharing(notifyPeer: Boolean = true) {
    // GPS callbacks may run on main looper; null-out first to avoid double-cancel races.
    val cancel = liveLocationCancel
    liveLocationCancel = null
    val job = liveLocationJob
    liveLocationJob = null
    val state = _uiState.value
    val messageId = state.activeLiveLocationMessageId
    val stoppedAt = System.currentTimeMillis()
    val terminalPayload = lastLiveLocationPayload
        ?.takeIf { it.sessionId == state.activeLiveLocationSessionId }
        ?.copy(capturedAt = stoppedAt, liveUntil = stoppedAt)
    lastLiveLocationPayload = null
    runCatching { cancel?.invoke() }
    job?.cancel()
    _uiState.update {
        it.copy(
            activeLiveLocationMessageId = null,
            activeLiveLocationSessionId = null,
            activeLiveLocationUntil = null
        )
    }
    if (notifyPeer && messageId != null && terminalPayload != null) {
        // 8.45：终态更新改走 applicationScope——此前依赖 viewModelScope，在
        // onCleared 路径（viewModelScope 已取消）下终态被丢弃，对端地图持续显示
        // live 位置直到原始过期时间。协程内引用仅用于网络发送，完成后即释放。
        com.maodouchat.MaodouchatApp.instance.applicationScope.launch {
            updateLiveLocationMessage(messageId, terminalPayload)
        }
    }
}

internal fun ChatDetailViewModel.startLiveLocationUpdates(messageId: String, sessionId: String, until: Long) {
    liveLocationCancel?.invoke()
    liveLocationCancel = null
    liveLocationJob?.cancel()
    val owner = currentUserId
    if (owner.isBlank() || sessionId.isBlank()) return
    val appCtx = getApplication<Application>()
    liveLocationJob = viewModelScope.launch(Dispatchers.IO) {
        var lastSentAt = 0L
        val cancel = com.maodouchat.util.LocationProvider.requestUpdates(appCtx) { location ->
            val now = System.currentTimeMillis()
            if (now >= until) {
                viewModelScope.launch(Dispatchers.Main.immediate) { stopLiveLocationSharing(notifyPeer = false) }
                return@requestUpdates
            }
            if (now - lastSentAt < com.maodouchat.util.LiveLocationPolicy.MIN_UPDATE_INTERVAL_MS) return@requestUpdates
            lastSentAt = now
            viewModelScope.launch {
                pushLiveLocationUpdate(messageId, sessionId, until, location)
            }
        }
        liveLocationCancel = cancel
        // Also tick periodically even if GPS quiet, and auto-stop at expiry.
        while (isActive) {
            val state = _uiState.value
            if (state.activeLiveLocationSessionId != sessionId) break
            val u = state.activeLiveLocationUntil ?: until
            if (System.currentTimeMillis() >= u) {
                withContext(Dispatchers.Main) { stopLiveLocationSharing(notifyPeer = false) }
                break
            }
            kotlinx.coroutines.delay(5_000L)
        }
        cancel()
    }
}

internal suspend fun ChatDetailViewModel.pushLiveLocationUpdate(
    messageId: String,
    sessionId: String,
    until: Long,
    location: android.location.Location
) {
    val sendOwnerUserId = currentUserId
    if (token.isBlank() || sendOwnerUserId.isBlank()) return
    val state = _uiState.value
    if (state.activeLiveLocationSessionId != sessionId || state.activeLiveLocationMessageId != messageId) return
    val now = System.currentTimeMillis()
    if (now >= until) {
        stopLiveLocationSharing(notifyPeer = false)
        return
    }
    val payload = com.maodouchat.data.model.LocationPayload(
        latitude = location.latitude,
        longitude = location.longitude,
        accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
        label = text(R.string.chat_live_location_send),
        capturedAt = location.time.takeIf { it > 0 } ?: now,
        live = true,
        liveUntil = until,
        sessionId = sessionId
    )
    lastLiveLocationPayload = payload
    updateLiveLocationMessage(messageId, payload)
}

internal suspend fun ChatDetailViewModel.updateLiveLocationMessage(
    messageId: String,
    payload: com.maodouchat.data.model.LocationPayload
) = liveLocationUpdateMutex.withLock {
    val ownerUserId = currentUserId
    if (token.isBlank() || ownerUserId.isBlank()) return@withLock
    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
        expectedUserId = ownerUserId,
    )
    ) return@withLock
    val stagedOriginal = _uiState.value.messages.firstOrNull { it.id == messageId }
        ?.takeIf { it.senderId == ownerUserId && it.type == MessageType.LOCATION }
        ?: return@withLock
    val content = kotlinx.serialization.json.Json.encodeToString(
        com.maodouchat.data.model.LocationPayload.serializer(),
        payload
    )
    val stagedUpdate = stagedOriginal.toOptimisticEdit(content)
    val terminalUpdate = payload.liveUntil?.let { it <= System.currentTimeMillis() + 1_000L } == true
    if (terminalUpdate) {
        _uiState.update { state ->
            state.copy(messages = state.messages.map { if (it.id == messageId) stagedUpdate else it })
        }
        withContext(Dispatchers.IO) { messageRepo.insertMessage(stagedUpdate) }
    }
    if (inlineSendCompletions[messageId]?.await() == false) return@withLock
    val original = _uiState.value.messages.firstOrNull { it.id == messageId }
        ?.takeIf { it.senderId == ownerUserId && it.type == MessageType.LOCATION }
        ?: stagedUpdate
    val updated = if (original.content == content) original else original.toOptimisticEdit(content)
    try {
        messagingMutationFacade.edit(
            updated = updated,
            ownerUserId = ownerUserId,
            groupRevision = currentGroupRevision(),
            indexForSearch = false,
            refreshPreview = false,
        )
        if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = ownerUserId,
        )
        ) {
            _uiState.update { state ->
                state.copy(messages = state.messages.map { if (it.id == messageId) updated else it })
            }
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w("ChatDetailViewModel", "Live location update failed", error)
    }
}

fun ChatDetailViewModel.sendCurrentLocation() {
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.STATIC_LOCATION)) {
        _uiState.update { it.copy(errorMessage = text(R.string.static_location_disabled)) }
        return
    }
    viewModelScope.launch {
        val locOwnerUserId = currentUserId
        if (token.isBlank() || locOwnerUserId.isBlank()) {
            _uiState.update {
                it.copy(isSending = false, groupEncryptionWarning = text(R.string.error_session_expired))
            }
            return@launch
        }
        _uiState.update { it.copy(isSending = true, groupEncryptionWarning = null) }
        try {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = locOwnerUserId,
            )
            ) {
                _uiState.update { it.copy(isSending = false, groupEncryptionWarning = text(R.string.error_session_expired)) }
                return@launch
            }
            com.maodouchat.util.LocationProvider.currentLocation(getApplication()).fold(
                onSuccess = { location ->
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = locOwnerUserId,
                    )
                    ) {
                        _uiState.update { it.copy(isSending = false, groupEncryptionWarning = text(R.string.error_session_expired)) }
                        return@fold
                    }
                    val payload = com.maodouchat.data.model.LocationPayload(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
                        capturedAt = location.time.takeIf { it > 0 } ?: System.currentTimeMillis()
                    )
                    val content = kotlinx.serialization.json.Json.encodeToString(
                        com.maodouchat.data.model.LocationPayload.serializer(),
                        payload
                    )
                    // sendInlineContent owns the isSending lifecycle from here.
                    sendInlineContent(content, MessageType.LOCATION, text(R.string.message_preview_location))
                },
                onFailure = { error ->
                    val message = when ((error as? com.maodouchat.util.LocationException)?.failure) {
                        com.maodouchat.util.LocationFailure.PERMISSION_REQUIRED -> text(R.string.location_error_permission)
                        com.maodouchat.util.LocationFailure.SERVICES_DISABLED -> text(R.string.location_error_services_disabled)
                        com.maodouchat.util.LocationFailure.UNAVAILABLE, null -> text(R.string.location_error_unavailable)
                    }
                    _uiState.update { it.copy(isSending = false, groupEncryptionWarning = message) }
                }
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            _uiState.update { it.copy(isSending = false) }
            throw error
        }
    }
}