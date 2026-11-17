package com.maodouchat.domain.messaging

// Agent 消息级操作的结果文案，自 app 侧 AgentMessagingMessageTools 收敛；
// 星标同步、撤回、回应等网络/数据库调用仍留在 app 侧。
object AgentMessageResultPolicy {

    fun alreadyStarred(starred: Boolean): String =
        "Already starred=$starred"

    fun starred(starred: Boolean, messageId: String): String =
        "Starred=$starred for $messageId"

    fun starredLocally(starred: Boolean, messageId: String): String =
        "Starred=$starred locally for $messageId (offline)"

    fun serverStarMismatch(starred: Boolean): String =
        "Error: server star state is $starred"

    fun deletedLocal(messageId: String): String =
        "Deleted local message $messageId"

    fun revoked(messageId: String): String =
        "Revoked $messageId"

    fun reactionUpdated(messageId: String, count: Int): String =
        "Reaction updated on $messageId count=$count"

    fun pinned(pinned: Boolean, messageId: String): String =
        "Pinned=$pinned for $messageId"
}
