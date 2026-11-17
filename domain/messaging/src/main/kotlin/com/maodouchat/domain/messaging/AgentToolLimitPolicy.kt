package com.maodouchat.domain.messaging

// Agent 工具分发默认上限：三簇 ToolHost 的 `args["limit"]?.toIntOrNull() ?: N`
// 逐字收敛，未登记的工具沿用历史最常见的 20。
object AgentToolLimitPolicy {

    fun defaultLimit(name: String): Int = when (name) {
        AgentToolNamePolicy.SEARCH_MESSAGES, AgentToolNamePolicy.LIST_MISSED_CALLS -> 12
        AgentToolNamePolicy.LIST_POST_COMMENTS -> 30
        AgentToolNamePolicy.GET_CHAT_HISTORY,
        AgentToolNamePolicy.LIST_STARRED_MESSAGES,
        AgentToolNamePolicy.SEARCH_USERS,
        AgentToolNamePolicy.LIST_POSTS,
        AgentToolNamePolicy.LIST_LOCAL_TASKS,
        AgentToolNamePolicy.LIST_NOTIFICATIONS -> 20
        else -> 20
    }
}
