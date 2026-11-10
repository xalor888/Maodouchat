package com.maodouchat.ui.screen.call

import android.util.Log
import com.maodouchat.core.session.CallSignalingOutboundCursor
import com.maodouchat.domain.calls.CallHangUpGateway
import com.maodouchat.domain.calls.CallHangUpSender
import com.maodouchat.domain.calls.CallHangUpSession
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.session.AppRuntime
import com.maodouchat.session.CurrentSession
import com.maodouchat.webrtc.WebRTCSignaling

/** 挂断信令网络端口的 app 侧实现：直通 WebRTCSignaling。 */
internal class WebRtcCallHangUpGateway : CallHangUpGateway {
    override suspend fun hangUp(
        token: String,
        toUserId: String,
        callId: String,
        groupId: String,
        groupMemberIds: List<String>,
        epoch: Long,
        sequence: Long,
        idempotencyKey: String,
    ): Result<Unit> = WebRTCSignaling.hangUp(
        token, toUserId, callId, groupId, groupMemberIds, epoch, sequence, idempotencyKey,
    )

    override fun sendViaWebSocket(
        toUserId: String,
        type: String,
        payload: String,
        callId: String,
        groupId: String,
        groupMemberIds: List<String>,
        groupInvite: Boolean,
        epoch: Long,
        sequence: Long,
        idempotencyKey: String,
    ): Boolean = WebRTCSignaling.sendViaWebSocket(
        toUserId, type, payload, callId, groupId, groupMemberIds, groupInvite,
        epoch, sequence, idempotencyKey,
    )
}

/** 挂断会话快照端口的 app 侧实现：直通 CurrentSession / BackgroundSessionGate。 */
internal object CurrentSessionCallHangUpSession : CallHangUpSession {
    override fun ownerUserId(): String = CurrentSession.ownerUserId()
    override fun token(): String = CurrentSession.snapshot().token.orEmpty()
    override fun mayContinue(expectedUserId: String): Boolean =
        BackgroundSessionGate.mayContinue(expectedUserId)
}

/** CallViewModel 的挂断发送器接线：网络 / 会话 / 作用域 / 日志全部由 app 侧提供。 */
internal fun createCallHangUpSender(
    outboundSignalingCursor: CallSignalingOutboundCursor,
): CallHangUpSender = CallHangUpSender(
    outboundSignalingCursor = outboundSignalingCursor,
    gateway = WebRtcCallHangUpGateway(),
    session = CurrentSessionCallHangUpSession,
    scope = AppRuntime.applicationScope,
    logWarning = { message, error -> Log.w("CallViewModel", message, error) },
)
