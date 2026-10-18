package com.maodouchat.ui.screen.chatdetail

import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.data.model.LocationPayload
import com.maodouchat.data.model.MessageType
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.util.LocationException
import com.maodouchat.util.LocationFailure
import com.maodouchat.util.LocationProvider
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


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
                    val payload = LocationPayload(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
                        capturedAt = location.time.takeIf { it > 0 } ?: System.currentTimeMillis()
                    )
                    val content = Json.encodeToString(
                        LocationPayload.serializer(),
                        payload
                    )
                    // sendInlineContent owns the isSending lifecycle from here.
                    sendInlineContent(content, MessageType.LOCATION, text(R.string.message_preview_location))
                },
                onFailure = { error ->
                    val message = when ((error as? LocationException)?.failure) {
                        LocationFailure.PERMISSION_REQUIRED -> text(R.string.location_error_permission)
                        LocationFailure.SERVICES_DISABLED -> text(R.string.location_error_services_disabled)
                        LocationFailure.UNAVAILABLE, null -> text(R.string.location_error_unavailable)
                    }
                    _uiState.update { it.copy(isSending = false, groupEncryptionWarning = message) }
                }
            )
        } catch (error: CancellationException) {
            _uiState.update { it.copy(isSending = false) }
            throw error
        }
    }
}
