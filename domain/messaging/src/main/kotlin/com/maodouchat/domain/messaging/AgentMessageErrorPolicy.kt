package com.maodouchat.domain.messaging

// Agent 消息级操作错误文案：查无消息、仅发送者可撤回、星标同步失败。
// 自 app 侧 AgentMessagingMessageTools 收敛，文案逐字一致。
object AgentMessageErrorPolicy {

    fun messageNotFound(): String = "Error: message not found"

    fun onlySenderCanRevoke(): String = "Error: only the sender can revoke this message"

    fun starSyncFailed(message: String?): String = "Error: ${message ?: "star sync failed"}"
}
