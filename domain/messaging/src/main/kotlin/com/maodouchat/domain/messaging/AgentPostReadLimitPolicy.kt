package com.maodouchat.domain.messaging

// Agent 动态只读查询的条数边界，自 app 侧 ai/agent 社交读簇收敛；
// 网络仍在 app 侧，这里只定边界。
object AgentPostReadLimitPolicy {

    fun postsLimit(limit: Int): Int = limit.coerceIn(1, 40)

    fun commentsLimit(limit: Int): Int = limit.coerceIn(1, 100)
}
