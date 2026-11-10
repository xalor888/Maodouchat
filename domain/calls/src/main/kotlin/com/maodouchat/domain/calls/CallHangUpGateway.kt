package com.maodouchat.domain.calls

/** 挂断信令发送的网络端口。app 侧直通 WebRTCSignaling。 */
interface CallHangUpGateway {
    suspend fun hangUp(
        token: String,
        toUserId: String,
        callId: String,
        groupId: String,
        groupMemberIds: List<String>,
        epoch: Long,
        sequence: Long,
        idempotencyKey: String,
    ): Result<Unit>

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
}

/** 挂断时读取的会话快照端口。app 侧直通 CurrentSession / BackgroundSessionGate。 */
interface CallHangUpSession {
    fun ownerUserId(): String
    fun token(): String
    fun mayContinue(expectedUserId: String): Boolean
}
