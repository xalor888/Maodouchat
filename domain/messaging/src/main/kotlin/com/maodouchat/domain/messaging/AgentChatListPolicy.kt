package com.maodouchat.domain.messaging


// Agent 会话列表/详情的纯展示逻辑，自 app 侧 ai/agent 会话读簇收敛；
// 查库与门闩仍在 app 侧，这里只定标题、标记、查询匹配与历史条数边界。
object AgentChatListPolicy {

    fun normalizeQuery(query: String?): String = query?.trim()?.lowercase().orEmpty()

    fun matchesQuery(groupName: String?, lastMessage: String, query: String): Boolean =
        query.isBlank() || (groupName.orEmpty() + " " + lastMessage).lowercase().contains(query)

    fun listTitle(groupName: String?, participantIds: String, id: String): String =
        groupName?.takeIf { it.isNotBlank() }
            ?: participantIds.split(",").firstOrNull { it.isNotBlank() }
            ?: id

    fun detailTitle(groupName: String?, participantIds: String): String =
        groupName?.takeIf { it.isNotBlank() } ?: participantIds

    fun chatFlags(
        isGroup: Boolean,
        chatType: String,
        id: String,
        secretIds: Set<String>,
        lockedIds: Set<String>,
        notificationsMuted: Boolean,
        pinnedAt: Long,
        archived: Boolean,
        unreadCount: Int,
    ): String = buildList {
        if (isGroup) add("group")
        if (chatType == "CHANNEL") add("channel")
        if (id in secretIds) add("secret")
        if (id in lockedIds) add("pin")
        if (notificationsMuted) add("muted")
        if (pinnedAt > 0) add("pinned")
        if (archived) add("archived")
        if (unreadCount > 0) add("unread=$unreadCount")
    }.joinToString(",")

    fun historyLimit(limit: Int): Int = limit.coerceIn(1, AgentToolPolicy.MAX_CHAT_HISTORY)
}
