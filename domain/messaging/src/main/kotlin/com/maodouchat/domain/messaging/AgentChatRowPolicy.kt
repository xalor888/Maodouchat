package com.maodouchat.domain.messaging

// Agent 会话列表行与详情行的纯格式化逻辑，自 app 侧 ai/agent 会话读簇收敛；
// 查库与门闩仍在 app 侧，这里只定行格式。
object AgentChatRowPolicy {

    fun chatListRow(id: String, title: String, lastPreview: String, flags: String): String =
        "$id\t$title\t$lastPreview\t$flags"

    fun chatDetail(
        id: String,
        title: String,
        chatType: String,
        muted: Boolean,
        pinned: Boolean,
        archived: Boolean,
        unread: Int,
        markedUnread: Boolean,
        lastPreview: String,
    ): String = buildString {
        appendLine("id=$id")
        appendLine("title=$title")
        appendLine("type=$chatType")
        appendLine("muted=$muted")
        appendLine("pinned=$pinned")
        appendLine("archived=$archived")
        appendLine("unread=$unread")
        appendLine("markedUnread=$markedUnread")
        appendLine("last=$lastPreview")
    }.trim()
}
