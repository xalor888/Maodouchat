package com.maodouchat.ui.screen.chatdetail

import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.data.model.LocationPayload
import com.maodouchat.data.model.MessageType
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.session.AppRuntime
import com.maodouchat.util.LiveLocationPolicy
import com.maodouchat.util.LocationProvider
import com.maodouchat.util.RuntimeFlags
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


fun ChatDetailViewModel.sendLiveLocation(durationMs: Long = LiveLocationPolicy.DURATION_OPTIONS_MS.first()) {
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
            if (!BackgroundSessionGate.mayContinue(
                expectedUserId = locOwnerUserId,
            )
            ) {
                _uiState.update { it.copy(isSending = false, groupEncryptionWarning = text(R.string.error_session_expired)) }
                return@launch
            }
            LocationProvider.currentLocation(getApplication()).fold(
                onSuccess = { location ->
                    if (!BackgroundSessionGate.mayContinue(
                        expectedUserId = locOwnerUserId,
                    )
                    ) {
                        _uiState.update { it.copy(isSending = false, groupEncryptionWarning = text(R.string.error_session_expired)) }
                        return@fold
                    }
                    val now = System.currentTimeMillis()
                    val sessionId = "live_" + UUID.randomUUID().toString().replace("-", "").take(12)
                    val until = now + durationMs.coerceIn(
                        LiveLocationPolicy.DURATION_OPTIONS_MS.first(),
                        LiveLocationPolicy.DURATION_OPTIONS_MS.last()
                    )
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
                    val content = Json.encodeToString(
                        LocationPayload.serializer(),
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
        } catch (e: CancellationException) {
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
        AppRuntime.applicationScope.launch {
            updateLiveLocationMessage(messageId, terminalPayload)
        }
    }
}
