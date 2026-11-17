package com.maodouchat.domain.messaging

// Agent 侧新建实体的 id 前缀：任务 task_、消息 m_、自配端点 p_，
// 后缀统一取 UUID 字符串。自三处调用方收敛，前缀逐字一致。
object AgentEntityIdPolicy {

    fun taskId(uuid: String): String = "task_$uuid"

    fun messageId(uuid: String): String = "m_$uuid"

    fun providerId(uuid: String): String = "p_$uuid"
}
