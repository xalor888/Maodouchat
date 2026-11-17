package com.maodouchat.domain.messaging

// Agent 置顶消息行的纯格式化逻辑，自 app 侧 ai/agent 消息读簇收敛。
object AgentPinnedLinePolicy {

    fun pinnedLine(messageId: String, pinnedBy: String, pinnedAt: Long): String =
        "$messageId\tby=$pinnedBy\tat=$pinnedAt"
}
