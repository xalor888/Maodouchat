package com.maodouchat.ui.screen.call

import com.maodouchat.R
import com.maodouchat.call.CallSystemIntegration
import com.maodouchat.webrtc.CallType

// 前台服务起停一族：从 CallViewModel 纯搬移；VM 留同签名委托。
internal class CallForegroundServiceController(
    private val systemIntegration: CallSystemIntegration,
    private val currentState: () -> CallUiState,
    private val activeCallId: () -> String,
    private val text: (Int, Array<out Any>) -> String,
) {
    fun start() {
        val state = currentState()
        systemIntegration.startCallForeground(
            contactName = state.contactName.ifBlank { text(R.string.call_unknown_caller, emptyArray()) },
            isVideo = state.callType == CallType.VIDEO,
            callId = activeCallId(),
        )
    }

    fun stop() {
        systemIntegration.stopCallForeground()
    }
}
