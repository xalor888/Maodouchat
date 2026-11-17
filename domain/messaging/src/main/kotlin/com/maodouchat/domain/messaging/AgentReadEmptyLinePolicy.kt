package com.maodouchat.domain.messaging

// Agent 读操作查无结果时返回的空结果行，自 app 侧 ai/agent 读簇收敛；
// 查询与行装配仍留在 app 侧，这里只定空结果文案本身。
object AgentReadEmptyLinePolicy {

    const val NO_CHATS: String = "No chats."

    const val NO_MESSAGES: String = "No messages."

    const val NO_MATCHES: String = "No matches."

    const val NO_STARRED_MESSAGES: String = "No starred messages."

    const val NO_PINNED_MESSAGES: String = "No pinned messages."

    const val NO_CONTACTS: String = "No contacts."

    const val NO_FRIEND_REQUESTS: String = "No friend requests."

    const val NO_FRIENDS: String = "No friends."

    const val NO_USERS: String = "No users."

    const val NO_BLOCKED_USERS: String = "No blocked users."

    const val NO_POSTS: String = "No posts."

    const val NO_COMMENTS: String = "No comments."
}
