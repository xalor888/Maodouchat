package com.maodouchat.domain.messaging

// Agent 会话/联系人/通知设置错误文案：查无联系人、查无通知、未提供更新字段。
// 自 app 侧 AgentMessagingChatTools 收敛，文案逐字一致。
object AgentChatErrorPolicy {

    fun contactNotFound(): String = "Error: contact not found"

    fun notificationNotFound(): String = "Error: notification not found"

    fun provideUpdateFields(): String =
        "Error: provide pinned, muted, archived, or markedUnread"
}
