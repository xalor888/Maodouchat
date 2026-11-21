package com.maodouchat.ai.agent

import com.maodouchat.data.repository.ChatRepository
import com.maodouchat.data.repository.UserRepository
import com.maodouchat.domain.messaging.AgentChatErrorPolicy
import com.maodouchat.domain.messaging.AgentChatResultPolicy
import com.maodouchat.domain.messaging.AgentChatUpdatePolicy
import com.maodouchat.domain.messaging.AgentWriteErrorPolicy
import com.maodouchat.domain.messaging.AgentWriteInputPolicy

// 会话与联系人设置：会话置顶/静音/归档/未读、联系人备注、通知已读。
internal object AgentMessagingChatTools {
        internal suspend fun updateChat(ports: AgentToolPorts, args: Map<String, String>): String {
            val chatId = args["chatId"].orEmpty()
            if (chatId.isBlank()) return AgentWriteErrorPolicy.required("chatId")
            if (ports.database.chatDao().getChatById(chatId) == null) return AgentWriteErrorPolicy.chatNotFound()
            AgentMessagingReadGates.denySecretOrLockedChat(ports, chatId)?.let { return it }
            val repo = ChatRepository(ports.database.chatDao(), ports.database.userDao())
            val entries = AgentChatUpdatePolicy.updateEntries(args)
            for ((key, value) in entries) {
                when (key) {
                    "pinned" -> if (value) repo.pinChat(chatId) else repo.unpinChat(chatId)
                    "muted" -> if (value) repo.muteChat(chatId) else repo.unmuteChat(chatId)
                    "archived" -> if (value) repo.archiveChat(chatId) else repo.unarchiveChat(chatId)
                    "markedUnread" -> if (value) repo.markChatUnread(chatId) else repo.markChatRead(chatId)
                }
            }
            if (entries.isEmpty()) return AgentChatErrorPolicy.provideUpdateFields()
            return AgentChatUpdatePolicy.formatUpdated(chatId, entries)
        }

        internal suspend fun setNickname(ports: AgentToolPorts, userId: String, nickname: String): String {
            if (userId.isBlank()) return AgentWriteErrorPolicy.required("userId")
            if (ports.database.userDao().getUserById(userId) == null) return AgentChatErrorPolicy.contactNotFound()
            UserRepository(ports.database.userDao()).setNickname(userId, AgentWriteInputPolicy.cleanNickname(nickname))
            return AgentChatResultPolicy.nicknameResult(userId, nickname)
        }

        internal fun markNotificationRead(ports: AgentToolPorts, itemId: String): String {
            if (itemId.isBlank()) return AgentWriteErrorPolicy.required("itemId")
            val exists = ports.notificationCenter.items.value.any { it.id == itemId }
            if (!exists) return AgentChatErrorPolicy.notificationNotFound()
            ports.notificationCenter.markRead(itemId)
            return AgentChatResultPolicy.markedRead(itemId)
        }
}
