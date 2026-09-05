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

interface AgentPrivacyPort {
    suspend fun checkReadableChat(chatId: String): String?
    suspend fun isChatAllowedForAi(chatId: String): Boolean
    suspend fun getLockedAndSecretChatIds(): Pair<Set<String>, Set<String>>
    suspend fun getBlockedChatIds(): Set<String>
    suspend fun getSecretPeerIds(): Set<String>
}

interface AgentChatPort {
    suspend fun listChats(query: String?): String
    suspend fun getChat(chatId: String): String
    suspend fun updateChat(args: Map<String, String>): String
    suspend fun deleteChat(chatId: String): String
    suspend fun createDirect(userId: String): String
    suspend fun createGroup(name: String, memberIds: String): String
    suspend fun renameGroup(chatId: String, name: String): String
    suspend fun updateAnnouncement(chatId: String, announcement: String): String
    suspend fun addMembers(chatId: String, memberIds: String): String
    suspend fun removeMember(chatId: String, memberId: String): String
    suspend fun muteMember(chatId: String, memberId: String, mutedUntil: Long): String
}

interface AgentMessagePort {
    suspend fun getChatHistory(chatId: String, limit: Int): String
    suspend fun searchMessages(query: String, limit: Int): String
    suspend fun sendText(chatId: String, text: String): String
    suspend fun listStarred(limit: Int): String
    suspend fun starMessage(messageId: String, starred: Boolean): String
    suspend fun deleteLocalMessage(messageId: String): String
    suspend fun listPinned(chatId: String): String
    suspend fun pinMessage(chatId: String, messageId: String): String
    suspend fun revokeMessage(messageId: String): String
    suspend fun react(messageId: String, emoji: String): String
}

interface AgentContactPort {
    suspend fun getContacts(query: String?): String
    suspend fun getMe(): String
    suspend fun setNickname(userId: String, nickname: String): String
    suspend fun listFriends(): String
    suspend fun listFriendRequests(direction: String): String
    suspend fun sendFriend(userId: String, message: String): String
    suspend fun acceptFriend(requestId: String): String
    suspend fun rejectFriend(requestId: String): String
    suspend fun cancelFriend(requestId: String): String
    suspend fun removeFriend(userId: String): String
    suspend fun blockUser(userId: String): String
    suspend fun unblockUser(userId: String): String
    suspend fun searchUsers(query: String, limit: Int): String
    suspend fun listBlocked(): String
}

interface AgentTaskPort {
    suspend fun listDrafts(): String
    suspend fun getDraft(chatId: String): String
    suspend fun setDraft(chatId: String, text: String): String
    suspend fun listTasks(limit: Int): String
    suspend fun createTask(chatId: String, title: String, dueText: String?): String
    suspend fun completeTask(taskId: String, completed: Boolean): String
    suspend fun deleteTask(taskId: String): String
    suspend fun listMissedCalls(limit: Int): String
    suspend fun listNotifications(limit: Int): String
    suspend fun markNotificationRead(id: String): String
}

interface AgentSocialPort {
    suspend fun listPosts(limit: Int): String
    suspend fun getPost(postId: String): String
    suspend fun listPostComments(postId: String, limit: Int): String
    suspend fun createPost(text: String, visibility: String?): String
    suspend fun likePost(postId: String, liked: Boolean): String
    suspend fun commentPost(postId: String, text: String): String
    suspend fun deletePost(postId: String): String
}

data class AgentDomainPorts(
    val chat: AgentChatPort,
    val message: AgentMessagePort,
    val contact: AgentContactPort,
    val task: AgentTaskPort,
    val social: AgentSocialPort,
    val privacy: AgentPrivacyPort
)
