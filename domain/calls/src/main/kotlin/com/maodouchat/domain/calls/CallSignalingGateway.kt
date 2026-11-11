package com.maodouchat.domain.calls

// 出站信令发送的网络端口：domain 侧只定形状，WS/REST 真实现由 app 侧 WebRTCSignaling 提供。
interface CallSignalingGateway {
    fun sendViaWebSocket(
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
    ): Boolean

    suspend fun sendViaRest(
        token: String,
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
    ): Result<Unit>
}
