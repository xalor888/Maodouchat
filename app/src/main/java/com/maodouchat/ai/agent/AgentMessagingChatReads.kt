package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.security.ChatLockSession

// 会话只读查询：列表、详情、历史（门闩复用 AgentMessagingReadGates）。
internal object AgentMessagingChatReads {
        internal suspend fun listChats(app: MaodouchatApp, query: String?): String {
            val all = app.database.chatDao().getAllChatsDirect()
            val q = query?.trim()?.lowercase().orEmpty()
            val locked = app.database.chatLockDao().listLockedChatIds().toHashSet()
            val secret = app.database.chatDao().listSecretChatIds().toHashSet()
            val lines = all.asSequence()
                .filter { chat ->
                    if (!AgentSecretGatePolicy.includeInChatList(
                            isSecret = chat.id in secret,
                            isLocked = chat.id in locked,
                            unlocked = ChatLockSession.isUnlocked(chat.id)
                        )
                    ) return@filter false
                    if (q.isBlank()) true
                    else (chat.groupName.orEmpty() + " " + chat.lastMessage).lowercase().contains(q)
                }
                .take(AgentToolPolicy.MAX_LIST_CHATS)
                .map { chat ->
                    val title = chat.groupName?.takeIf { it.isNotBlank() }
                        ?: chat.participantIds.split(",").firstOrNull { it.isNotBlank() }
                        ?: chat.id
                    val flags = buildList {
                        if (chat.isGroup) add("group")
                        if (chat.chatType == "CHANNEL") add("channel")
                        if (chat.id in secret) add("secret")
                        if (chat.id in locked) add("pin")
                        if (chat.notificationsMuted) add("muted")
                        if (chat.pinnedAt > 0) add("pinned")
                        if (chat.archived) add("archived")
                        if (chat.unreadCount > 0) add("unread=${chat.unreadCount}")
                    }.joinToString(",")
                    "${chat.id}\t$title\t${chat.lastMessage.take(80)}\t$flags"
                }
                .toList()
            return if (lines.isEmpty()) "No chats." else lines.joinToString("\n")
        }

        internal suspend fun getChat(app: MaodouchatApp, chatId: String): String {
            AgentMessagingReadGates.requireReadableChat(app, chatId)?.let { return it }
            val chat = app.database.chatDao().getChatById(chatId) ?: return "Error: chat not found"
            val title = chat.groupName?.takeIf { it.isNotBlank() }
                ?: chat.participantIds
            return buildString {
                appendLine("id=${chat.id}")
                appendLine("title=$title")
                appendLine("type=${chat.chatType}")
                appendLine("muted=${chat.notificationsMuted}")
                appendLine("pinned=${chat.pinnedAt > 0}")
                appendLine("archived=${chat.archived}")
                appendLine("unread=${chat.unreadCount}")
                appendLine("markedUnread=${chat.markedUnread}")
                appendLine("last=${chat.lastMessage.take(160)}")
            }.trim()
        }

        internal suspend fun getChatHistory(app: MaodouchatApp, chatId: String, limit: Int): String {
            AgentMessagingReadGates.requireReadableChat(app, chatId)?.let { return it }
            val messages = LocalMessageStore(app.database.messageDao(), app.database)
                .getRecentMessages(chatId, limit.coerceIn(1, AgentToolPolicy.MAX_CHAT_HISTORY))
            if (messages.isEmpty()) return "No messages."
            return messages.asReversed().joinToString("\n") { AgentMessagingMessageReads.formatMessage(it) }
        }
}
