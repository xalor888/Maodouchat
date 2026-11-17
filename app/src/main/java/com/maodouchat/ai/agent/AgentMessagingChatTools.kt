package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.data.repository.ChatRepository
import com.maodouchat.data.repository.UserRepository
import com.maodouchat.domain.messaging.AgentChatUpdatePolicy
import com.maodouchat.domain.messaging.AgentWriteInputPolicy

// 会话与联系人设置：会话置顶/静音/归档/未读、联系人备注、通知已读。
internal object AgentMessagingChatTools {
        internal suspend fun updateChat(app: MaodouchatApp, args: Map<String, String>): String {
            val chatId = args["chatId"].orEmpty()
            if (chatId.isBlank()) return "Error: chatId required"
            if (app.database.chatDao().getChatById(chatId) == null) return "Error: chat not found"
            AgentMessagingReadGates.denySecretOrLockedChat(app, chatId)?.let { return it }
            val repo = ChatRepository(app.database.chatDao(), app.database.userDao())
            val entries = AgentChatUpdatePolicy.updateEntries(args)
            for ((key, value) in entries) {
                when (key) {
                    "pinned" -> if (value) repo.pinChat(chatId) else repo.unpinChat(chatId)
                    "muted" -> if (value) repo.muteChat(chatId) else repo.unmuteChat(chatId)
                    "archived" -> if (value) repo.archiveChat(chatId) else repo.unarchiveChat(chatId)
                    "markedUnread" -> if (value) repo.markChatUnread(chatId) else repo.markChatRead(chatId)
                }
            }
            if (entries.isEmpty()) return "Error: provide pinned, muted, archived, or markedUnread"
            return AgentChatUpdatePolicy.formatUpdated(chatId, entries)
        }

        internal suspend fun setNickname(app: MaodouchatApp, userId: String, nickname: String): String {
            if (userId.isBlank()) return "Error: userId required"
            if (app.database.userDao().getUserById(userId) == null) return "Error: contact not found"
            UserRepository(app.database.userDao()).setNickname(userId, AgentWriteInputPolicy.cleanNickname(nickname))
            return if (nickname.isBlank()) "Cleared nickname for $userId" else "Set nickname for $userId"
        }

        internal fun markNotificationRead(app: MaodouchatApp, itemId: String): String {
            if (itemId.isBlank()) return "Error: itemId required"
            val exists = app.notificationCenter.items.value.any { it.id == itemId }
            if (!exists) return "Error: notification not found"
            app.notificationCenter.markRead(itemId)
            return "Marked read $itemId"
        }
}
