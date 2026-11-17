package com.maodouchat.domain.messaging

// Agent 工具分发默认上限：三簇 ToolHost 的 `args["limit"]?.toIntOrNull() ?: N`
// 逐字收敛，未登记的工具沿用历史最常见的 20。
object AgentToolLimitPolicy {

    fun defaultLimit(name: String): Int = when (name) {
        "search_messages", "list_missed_calls" -> 12
        "list_post_comments" -> 30
        "get_chat_history",
        "list_starred_messages",
        "search_users",
        "list_posts",
        "list_local_tasks",
        "list_notifications" -> 20
        else -> 20
    }
}
