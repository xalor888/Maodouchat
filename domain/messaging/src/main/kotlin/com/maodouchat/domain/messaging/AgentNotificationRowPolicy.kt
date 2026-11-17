package com.maodouchat.domain.messaging

// Agent 通知中心行的纯格式化逻辑，自 app 侧 ai/agent 任务读簇收敛；
// 可见性判定与截断由调用方先行，这里只定行格式。
object AgentNotificationRowPolicy {

    fun notificationRow(id: String, type: String, title: String, preview: String, read: Boolean): String =
        "$id\t$type\t$title\t$preview\tread=$read"
}
