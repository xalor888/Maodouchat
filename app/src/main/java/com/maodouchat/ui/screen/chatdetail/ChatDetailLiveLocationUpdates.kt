package com.maodouchat.ui.screen.chatdetail

import android.app.Application
import android.location.Location
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.data.model.LocationPayload
import com.maodouchat.data.model.MessageType
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.util.LiveLocationPolicy
import com.maodouchat.util.LocationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


internal fun ChatDetailViewModel.startLiveLocationUpdates(messageId: String, sessionId: String, until: Long) {
    liveLocationCancel?.invoke()
    liveLocationCancel = null
    liveLocationJob?.cancel()
    val owner = currentUserId
    if (owner.isBlank() || sessionId.isBlank()) return
    val appCtx = getApplication<Application>()
    liveLocationJob = viewModelScope.launch(Dispatchers.IO) {
        var lastSentAt = 0L
        val cancel = LocationProvider.requestUpdates(appCtx) { location ->
            val now = System.currentTimeMillis()
            if (now >= until) {
                viewModelScope.launch(Dispatchers.Main.immediate) { stopLiveLocationSharing(notifyPeer = false) }
                return@requestUpdates
            }
            if (now - lastSentAt < LiveLocationPolicy.MIN_UPDATE_INTERVAL_MS) return@requestUpdates
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
            delay(5_000L)
        }
        cancel()
    }
}

internal suspend fun ChatDetailViewModel.pushLiveLocationUpdate(
    messageId: String,
    sessionId: String,
    until: Long,
    location: Location
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
    val payload = LocationPayload(
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
    payload: LocationPayload
) = liveLocationUpdateMutex.withLock {
    val ownerUserId = currentUserId
    if (token.isBlank() || ownerUserId.isBlank()) return@withLock
    if (!BackgroundSessionGate.mayContinue(
        expectedUserId = ownerUserId,
    )
    ) return@withLock
    val stagedOriginal = _uiState.value.messages.firstOrNull { it.id == messageId }
        ?.takeIf { it.senderId == ownerUserId && it.type == MessageType.LOCATION }
        ?: return@withLock
    val content = Json.encodeToString(
        LocationPayload.serializer(),
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
        if (BackgroundSessionGate.mayContinue(
            expectedUserId = ownerUserId,
        )
        ) {
            _uiState.update { state ->
                state.copy(messages = state.messages.map { if (it.id == messageId) updated else it })
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w("ChatDetailViewModel", "Live location update failed", error)
    }
}
