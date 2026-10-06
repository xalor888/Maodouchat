package com.maodouchat.ui.screen.call

import com.maodouchat.call.CallSignalingOutboundCursor
import com.maodouchat.session.AppRuntime
import com.maodouchat.webrtc.WebRTCSignaling
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 挂断信令发送一族：从 CallViewModel 纯搬移；VM 留同签名委托。
internal class CallHangUpSender(
    private val outboundSignalingCursor: CallSignalingOutboundCursor,
) {
    /**
     * hang-up 走 applicationScope + REST 优先，避免 ViewModel 销毁后 viewModelScope 被取消导致对端一直响铃。
     */
    fun sendHangUpDurable(
        toUserId: String,
        callId: String,
        groupId: String,
        groupMembers: List<String>
    ) {
        if (toUserId.isBlank()) return
        // Capture owner at hang-up request time: after account switch, do not hang up under new session.
        val hangUpOwnerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (hangUpOwnerUserId.isBlank()) return
        val ticket = outboundSignalingCursor.next(callId, "hang-up")
        AppRuntime.applicationScope.launch {
            // 挂断必须尽量送达：进程/协程取消时仍跑 REST+WS，避免对端幽灵响铃
            withContext(kotlinx.coroutines.NonCancellable) {
                // Same owner + live token only; never hang-up under a switched account.
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(expectedUserId = hangUpOwnerUserId)) {
                    return@withContext
                }
                // 启动时再读一次会话令牌（可能刚 refresh，比上面捕获的更新）；
                // hangUp 内部 ApiService 仍会 401 重试
                val authToken = com.maodouchat.session.CurrentSession.snapshot().token.orEmpty()
                if (authToken.isBlank()) return@withContext
                // 走 /api/signaling/hangup：存 hang-up 并 clearForCallExcluding，避免离线仍响铃
                try {
                    WebRTCSignaling.hangUp(
                        authToken, toUserId, callId, groupId, groupMembers,
                        ticket.epoch, ticket.sequence, ticket.idempotencyKey,
                    )
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    android.util.Log.w("CallViewModel", "durable hang-up REST failed", error)
                }
                // REST 失败或 WS 更快送达时仍尽力推一条（仍要求同一 owner）
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(expectedUserId = hangUpOwnerUserId)) {
                    return@withContext
                }
                try {
                    WebRTCSignaling.sendViaWebSocket(
                        toUserId, "hang-up", "", callId, groupId, groupMembers, false,
                        ticket.epoch, ticket.sequence, ticket.idempotencyKey,
                    )
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    android.util.Log.w("CallViewModel", "durable hang-up WS failed", error)
                }
            }
        }
    }
}
