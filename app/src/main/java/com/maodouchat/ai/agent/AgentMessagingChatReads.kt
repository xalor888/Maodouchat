package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.core.model.AgentSecretGatePolicy
import com.maodouchat.core.model.AgentToolPolicy
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.domain.messaging.AgentChatListPolicy
import com.maodouchat.domain.messaging.AgentChatRowPolicy
import com.maodouchat.domain.messaging.AgentMessageLinePolicy
import com.maodouchat.domain.messaging.AgentReadPreviewPolicy
import com.maodouchat.security.ChatLockSession

// 会话只读查询：列表、详情、历史（门闩复用 AgentMessagingReadGates）。
internal object AgentMessagingChatReads {
        internal suspend fun listChats(app: MaodouchatApp, query: String?): String {
            val all = app.database.chatDao().getAllChatsDirect()
            val q = AgentChatListPolicy.normalizeQuery(query)
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
                    AgentChatListPolicy.matchesQuery(chat.groupName, chat.lastMessage, q)
                }
                .take(AgentToolPolicy.MAX_LIST_CHATS)
                .map { chat ->
                    val title = AgentChatListPolicy.listTitle(chat.groupName, chat.participantIds, chat.id)
                    val flags = AgentChatListPolicy.chatFlags(
                        isGroup = chat.isGroup,
                        chatType = chat.chatType,
                        id = chat.id,
                        secretIds = secret,
                        lockedIds = locked,
                        notificationsMuted = chat.notificationsMuted,
                        pinnedAt = chat.pinnedAt,
                        archived = chat.archived,
                        unreadCount = chat.unreadCount,
                    )
                    AgentChatRowPolicy.chatListRow(
                        chat.id,
                        title,
                        AgentReadPreviewPolicy.chatLastMessageList(chat.lastMessage),
                        flags,
                    )
                }
                .toList()
            return if (lines.isEmpty()) "No chats." else lines.joinToString("\n")
        }

        internal suspend fun getChat(app: MaodouchatApp, chatId: String): String {
            AgentMessagingReadGates.requireReadableChat(app, chatId)?.let { return it }
            val chat = app.database.chatDao().getChatById(chatId) ?: return "Error: chat not found"
            val title = AgentChatListPolicy.detailTitle(chat.groupName, chat.participantIds)
            return AgentChatRowPolicy.chatDetail(
                chat.id,
                title,
                chat.chatType,
                chat.notificationsMuted,
                chat.pinnedAt > 0,
                chat.archived,
                chat.unreadCount,
                chat.markedUnread,
                AgentReadPreviewPolicy.chatLastMessageDetail(chat.lastMessage),
            )
        }

        internal suspend fun getChatHistory(app: MaodouchatApp, chatId: String, limit: Int): String {
            AgentMessagingReadGates.requireReadableChat(app, chatId)?.let { return it }
            val messages = LocalMessageStore(app.database.messageDao(), app.database)
                .getRecentMessages(chatId, AgentChatListPolicy.historyLimit(limit))
            if (messages.isEmpty()) return "No messages."
            return messages.asReversed().joinToString("\n") { AgentMessageLinePolicy.formatMessage(it) }
        }
}
