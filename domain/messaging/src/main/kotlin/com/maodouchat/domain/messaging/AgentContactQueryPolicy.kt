package com.maodouchat.domain.messaging

// Agent 联系人只读查询的纯规整逻辑，自 app 侧 ai/agent 社交读簇收敛；
// 查库与网络仍在 app 侧，这里只定查询、展示名、方向判定与条数边界。
object AgentContactQueryPolicy {

    const val MAX_QUERY_CHARS: Int = 80

    const val MAX_CONTACT_ROWS: Int = 80

    const val MAX_FRIEND_REQUEST_ROWS: Int = 50

    fun normalizeQuery(query: String?): String = query?.trim().orEmpty()

    fun searchQuery(query: String): String = query.take(MAX_QUERY_CHARS)

    fun displayName(nickname: String?, name: String): String =
        nickname?.takeIf(String::isNotBlank) ?: name

    fun isIncoming(direction: String): Boolean = !direction.equals("outgoing", ignoreCase = true)

    fun userSearchLimit(limit: Int): Int = limit.coerceIn(1, 30)
}
