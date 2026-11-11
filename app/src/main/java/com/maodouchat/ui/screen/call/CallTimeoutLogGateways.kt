package com.maodouchat.ui.screen.call

import android.app.Application
import android.util.Log
import com.maodouchat.R
import com.maodouchat.call.CallLogStore
import com.maodouchat.call.MissedCallRecorder
import com.maodouchat.core.model.CallLogEntry
import com.maodouchat.core.model.CallSessionGate
import com.maodouchat.core.model.CallUiState
import com.maodouchat.domain.calls.CallLogWriter
import com.maodouchat.domain.calls.CallRingingTimeout
import com.maodouchat.domain.calls.CallRingingTimeoutMessage
import kotlinx.coroutines.CoroutineScope

/** 振铃超时文案种类到 R.string 的映射。 */
internal fun callRingingTimeoutMessageResId(message: CallRingingTimeoutMessage): Int = when (message) {
    CallRingingTimeoutMessage.CALL_NO_ANSWER -> R.string.call_no_answer
}

/** CallViewModel 的振铃超时接线：未接记录与失败日志由 app 侧提供。 */
internal fun createCallRingingTimeout(
    scope: CoroutineScope,
    app: Application,
    sessionGate: CallSessionGate,
    activeCallSession: () -> Long,
    currentState: () -> CallUiState,
    activeCallId: () -> String,
    timeoutMs: Long,
    errorMessages: CallErrorMessages,
    onNoAnswer: (String) -> Unit,
): CallRingingTimeout = CallRingingTimeout(
    scope = scope,
    sessionGate = sessionGate,
    activeCallSession = activeCallSession,
    currentState = currentState,
    activeCallId = activeCallId,
    timeoutMs = timeoutMs,
    text = { message -> errorMessages.text(callRingingTimeoutMessageResId(message)) },
    recordMissedCall = { signalingCallId, fromUserId, callerName, isVideo, isGroup ->
        MissedCallRecorder.recordRingTimeout(
            context = app,
            signalingCallId = signalingCallId,
            fromUserId = fromUserId,
            callerName = callerName,
            isVideo = isVideo,
            isGroup = isGroup,
        )
    },
    logWarn = { message, error -> Log.w("CallViewModel", message, error) },
    onNoAnswer = onNoAnswer,
)

/** CallViewModel 的通话记录写入接线：读写走 CallLogStore。 */
internal fun createCallLogWriter(
    app: Application,
    currentState: () -> CallUiState,
    activeCallId: () -> String,
    callLogOwnerUserId: () -> String,
): CallLogWriter = CallLogWriter(
    listCallLogs = { peerId -> CallLogStore.list(app, peerId) },
    upsertCallLog = { entry, expectedUserId ->
        CallLogStore.upsert(app, entry, expectedUserId = expectedUserId)
    },
    currentState = currentState,
    activeCallId = activeCallId,
    callLogOwnerUserId = callLogOwnerUserId,
)
