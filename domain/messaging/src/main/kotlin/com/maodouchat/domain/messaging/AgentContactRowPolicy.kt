package com.maodouchat.domain.messaging

// Agent 联系人/好友请求/好友/用户/拉黑行与本人详情的纯格式化逻辑，
// 自 app 侧 ai/agent 社交读簇收敛；查库与拉取仍在 app 侧，这里只定行格式。
object AgentContactRowPolicy {

    fun contactRow(id: String, displayName: String, statusPreview: String): String =
        "$id\t$displayName\t$statusPreview"

    fun personRow(id: String, name: String, statusPreview: String): String =
        "$id\t$name\t$statusPreview"

    fun friendRequestRow(
        id: String,
        status: String,
        fromId: String,
        fromName: String,
        toId: String,
        toName: String,
        messagePreview: String,
    ): String = "$id\t$status\tfrom=$fromId/$fromName\tto=$toId/$toName\t$messagePreview"

    fun blockedRow(id: String, name: String): String = "$id\t$name"

    // name 为 null 表示查无此人，只剩 userId 一行；调用方保证 name 非空时 status 亦非空。
    fun meDetail(userId: String, name: String?, nickname: String?, status: String?): String =
        buildString {
            appendLine("userId=$userId")
            if (name != null) {
                appendLine("name=$name")
                appendLine("nickname=${nickname.orEmpty()}")
                appendLine("status=$status")
            }
        }.trim()
}
