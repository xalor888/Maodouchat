package com.maodouchat.domain.messaging

// Agent 好友写操作的结果文案，自 app 侧 AgentFriendWriteTools 收敛；
// 网络请求仍留在 app 侧。
object AgentFriendResultPolicy {

    fun friendRequest(id: String, status: String): String =
        "Friend request $id status=$status"

    fun accepted(id: String, status: String): String =
        "Accepted $id status=$status"

    fun rejected(id: String, status: String): String =
        "Rejected $id status=$status"

    fun cancelled(id: String, status: String): String =
        "Cancelled $id status=$status"

    fun removed(userId: String): String =
        "Removed friend $userId"

    fun blocked(userId: String): String =
        "Blocked $userId"

    fun unblocked(userId: String): String =
        "Unblocked $userId"
}
