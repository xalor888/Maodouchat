package com.maodouchat.call

import com.maodouchat.MaodouchatApp
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Process-local bridge from the immutable foreground-service PendingIntent to the active call VM. */
object CallActionBus {
    data class HangUpRequest(
        val callId: String,
        val notifyPeer: Boolean = true,
        val sessionGeneration: Long = MaodouchatApp.currentSessionGeneration(),
    )

    private val _hangUpRequests = MutableSharedFlow<HangUpRequest>(extraBufferCapacity = 4)
    val hangUpRequests: SharedFlow<HangUpRequest> = _hangUpRequests.asSharedFlow()

    fun requestHangUp(callId: String, notifyPeer: Boolean = true): Boolean =
        _hangUpRequests.tryEmit(
            HangUpRequest(
                callId = callId,
                notifyPeer = notifyPeer,
                sessionGeneration = MaodouchatApp.currentSessionGeneration(),
            )
        )
}
