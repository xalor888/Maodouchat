package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallSessionGate
import com.maodouchat.core.model.CallState
import com.maodouchat.core.model.CallType
import com.maodouchat.core.model.CallUiState
import com.maodouchat.core.model.IncomingCallCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 振铃超时簇：去电 CALLING / 来电 RINGING 超时无应答后挂断并记未接：
 * 函数体从 `CallViewModel` 逐字搬入，VM 状态经 lambda 注入（与 `CallIceRecovery` 同一装配模式）。
 * 未接记录走 recordMissedCall 端口（app 侧 MissedCallRecorder，需 Context/Room），
 * 失败日志走 logWarn 端口，文案走 CallRingingTimeoutMessage 枚举。
 */
class CallRingingTimeout(
    private val scope: CoroutineScope,
    private val sessionGate: CallSessionGate,
    private val activeCallSession: () -> Long,
    private val currentState: () -> CallUiState,
    private val activeCallId: () -> String,
    private val timeoutMs: Long,
    private val text: (CallRingingTimeoutMessage) -> String,
    private val recordMissedCall: suspend (
        signalingCallId: String,
        fromUserId: String,
        callerName: String,
        isVideo: Boolean,
        isGroup: Boolean,
    ) -> Unit,
    private val logWarn: (message: String, error: Throwable) -> Unit,
    private val onNoAnswer: (String) -> Unit,
) {
    private var ringingTimeoutJob: Job? = null

    fun start(contactId: String) {
        ringingTimeoutJob?.cancel()
        val session = activeCallSession()
        ringingTimeoutJob = scope.launch {
            delay(timeoutMs)
            if (
                sessionGate.isCurrent(session) &&
                (currentState().callState == CallState.CALLING || currentState().callState == CallState.RINGING)
            ) {
                val st = currentState()
                // Incoming no-answer: list/tray missed row (idempotent with NavGraph timer
                // via stable callId REPLACE). Outgoing CALLING still only hangs up.
                if (st.isIncoming && st.callState == CallState.RINGING) {
                    try {
                        recordMissedCall(
                            activeCallId(),
                            st.contactId.ifBlank { contactId },
                            st.contactName,
                            st.callType == CallType.VIDEO,
                            st.isGroupCall,
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        logWarn("missed-call record on ring timeout failed", error)
                    }
                    IncomingCallCoordinator.clear()
                    // Peer already waited 30s; still send hang-up so their UI stops ringing.
                    onNoAnswer(text(CallRingingTimeoutMessage.CALL_NO_ANSWER))
                } else {
                    onNoAnswer(text(CallRingingTimeoutMessage.CALL_NO_ANSWER))
                }
            }
        }
    }

    fun cancel() {
        ringingTimeoutJob?.cancel()
        ringingTimeoutJob = null
    }
}
