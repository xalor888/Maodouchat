package com.maodouchat.domain.messaging

// 未接来电列表行格式，自 app 侧 ai/agent 任务读簇收敛；
// 查库与密聊过滤仍在 app 侧，这里只定行文本。
object AgentMissedCallLinePolicy {

    fun missedCallLine(
        id: String,
        callerId: String,
        callerName: String,
        callType: String,
        receivedAt: Long,
        isRead: Boolean,
    ): String =
        "$id\t$callerId\t$callerName\t$callType\t$receivedAt\tread=$isRead"
}
