package com.maodouchat.domain.calls

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Process-local bridge from the immutable foreground-service PendingIntent to the active call VM. */
object CallActionBus {
    private val _hangUpRequests = MutableSharedFlow<HangUpRequest>(extraBufferCapacity = 16)
    val hangUpRequests: SharedFlow<HangUpRequest> = _hangUpRequests.asSharedFlow()

    fun requestHangUp(callId: String, notifyPeer: Boolean = true, sessionGeneration: Long): Boolean =
        _hangUpRequests.tryEmit(
            HangUpRequest(
                callId = callId,
                notifyPeer = notifyPeer,
                sessionGeneration = sessionGeneration,
            )
        )
}
