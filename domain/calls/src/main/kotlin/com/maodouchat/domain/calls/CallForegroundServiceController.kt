package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallType
import com.maodouchat.core.model.CallUiState

// 前台服务起停一族：从 CallViewModel 纯搬移；VM 留同签名委托。
// 系统集成（前台服务实际起停）与未知来电文案走构造端口。
class CallForegroundServiceController(
    private val startCallForeground: (contactName: String, isVideo: Boolean, callId: String) -> Unit,
    private val stopCallForeground: () -> Unit,
    private val currentState: () -> CallUiState,
    private val activeCallId: () -> String,
    private val unknownCallerLabel: String,
) {
    fun start() {
        val state = currentState()
        // 函数类型值不接受具名实参，位置调用。
        startCallForeground(
            state.contactName.ifBlank { unknownCallerLabel },
            state.callType == CallType.VIDEO,
            activeCallId(),
        )
    }

    fun stop() {
        stopCallForeground()
    }
}
