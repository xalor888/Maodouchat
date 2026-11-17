package com.maodouchat.domain.messaging

// Agent 写操作工具的统一错误文案：必填字段、未登录、查无会话/任务。
// 自 app 侧五簇写操作工具（好友/群组/动态/任务/发送）收敛，文案逐字一致。
object AgentWriteErrorPolicy {

    fun required(vararg fields: String): String =
        "Error: " + fields.joinToString(" and ") + " required"

    fun notSignedIn(): String = "Error: not signed in"

    fun chatNotFound(): String = "Error: chat not found"

    fun taskNotFound(): String = "Error: task not found"
}
