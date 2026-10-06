package com.maodouchat.ui.screen.call

import android.content.Context
import android.util.Log
import com.maodouchat.R
import com.maodouchat.call.IncomingCallCoordinator
import com.maodouchat.call.MissedCallRecorder
import com.maodouchat.webrtc.CallSessionGate
import com.maodouchat.webrtc.CallState
import com.maodouchat.webrtc.CallType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 振铃超时簇：去电 CALLING / 来电 RINGING 超时无应答后挂断并记未接：
 * 函数体从 `CallViewModel` 逐字搬入，VM 状态经 lambda 注入（与 `CallIceRecovery` 同一装配模式）。
 */
internal class CallRingingTimeout(
    private val scope: CoroutineScope,
    private val context: Context,
    private val sessionGate: CallSessionGate,
    private val activeCallSession: () -> Long,
    private val currentState: () -> CallUiState,
    private val activeCallId: () -> String,
    private val timeoutMs: Long,
    private val text: (Int, Array<out Any>) -> String,
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
                        MissedCallRecorder.recordRingTimeout(
                            context = context,
                            signalingCallId = activeCallId(),
                            fromUserId = st.contactId.ifBlank { contactId },
                            callerName = st.contactName,
                            isVideo = st.callType == CallType.VIDEO,
                            isGroup = st.isGroupCall,
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w("CallViewModel", "missed-call record on ring timeout failed", error)
                    }
                    IncomingCallCoordinator.clear()
                    // Peer already waited 30s; still send hang-up so their UI stops ringing.
                    onNoAnswer(text(R.string.call_no_answer, emptyArray()))
                } else {
                    onNoAnswer(text(R.string.call_no_answer, emptyArray()))
                }
            }
        }
    }

    fun cancel() {
        ringingTimeoutJob?.cancel()
        ringingTimeoutJob = null
    }
}
