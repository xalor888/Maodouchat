package com.maodouchat.contacts.usecase

import com.maodouchat.data.model.User

/**
 * 好友申请模型
 */
data class FriendRequestItem(
    val id: String,
    val user: User,
    val message: String = "",
    val createdAt: Long = 0L,
    val outgoing: Boolean = false
) {
    val userId: String get() = user.id
    val userName: String get() = user.name
    val userAvatar: String? get() = user.avatar
    val note: String get() = message
    val isIncoming: Boolean get() = !outgoing
}

/**
 * 申请快照（包含收到的与发出的好友申请）
 */
data class FriendRequestsSnapshot(
    val incoming: List<FriendRequestItem> = emptyList(),
    val outgoing: List<FriendRequestItem> = emptyList()
)
