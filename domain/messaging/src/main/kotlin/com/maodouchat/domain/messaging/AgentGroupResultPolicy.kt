package com.maodouchat.domain.messaging

// Agent 群组/会话写操作的结果文案，自 app 侧 AgentGroupWriteTools 收敛；
// 网络/数据库调用仍留在 app 侧。
object AgentGroupResultPolicy {

    fun directChat(id: String): String =
        "Direct chat $id"

    fun group(id: String, name: String): String =
        "Group $id name=$name"

    fun renamed(chatId: String, name: String): String =
        "Renamed $chatId to $name"

    fun announcementUpdated(chatId: String): String =
        "Updated announcement for $chatId"

    fun membersAdded(count: Int, chatId: String): String =
        "Added $count members to $chatId"

    fun memberRemoved(memberId: String, chatId: String): String =
        "Removed $memberId from $chatId"

    fun muted(memberId: String, mutedUntil: Long): String =
        "Muted $memberId until $mutedUntil"

    fun chatDeleted(chatId: String): String =
        "Deleted chat $chatId"
}
