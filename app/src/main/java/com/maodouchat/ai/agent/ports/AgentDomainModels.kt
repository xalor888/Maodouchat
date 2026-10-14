package com.maodouchat.ai.agent.ports

data class AgentChatSummary(
    val id: String,
    val title: String,
    val lastMessage: String,
    val flags: String
)

data class AgentChatDetail(
    val id: String,
    val title: String,
    val type: String,
    val muted: Boolean,
    val pinned: Boolean,
    val archived: Boolean,
    val unreadCount: Int,
    val markedUnread: Boolean,
    val lastMessage: String
)

data class AgentMessageItem(
    val id: String,
    val senderId: String,
    val senderName: String,
    val timestamp: Long,
    val text: String,
    val type: String
)

data class AgentSearchResultItem(
    val chatId: String,
    val messageId: String,
    val snippet: String
)

data class AgentContactItem(
    val id: String,
    val name: String,
    val detail: String = ""
)

data class AgentTaskItem(
    val id: String,
    val chatId: String,
    val title: String,
    val dueText: String?,
    val completed: Boolean
)

data class AgentCallItem(
    val id: String,
    val callerId: String,
    val callerName: String,
    val callType: String,
    val receivedAt: Long,
    val isRead: Boolean
)

data class AgentNotificationItem(
    val id: String,
    val type: String,
    val title: String,
    val preview: String,
    val read: Boolean
)

data class AgentPostItem(
    val id: String,
    val authorId: String,
    val authorName: String,
    val content: String,
    val likeCount: Int,
    val likedByMe: Boolean
)

data class AgentPostCommentItem(
    val id: String,
    val postId: String,
    val authorId: String,
    val authorName: String,
    val content: String
)
