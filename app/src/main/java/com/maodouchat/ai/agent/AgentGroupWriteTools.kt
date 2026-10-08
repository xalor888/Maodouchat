package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.data.repository.ChatRepository
import com.maodouchat.network.ApiService

// Agent 工具执行簇：群组/会话写操作（创建单聊与群/改名/公告/成员管理/解散）。
// 从 AgentMessagingWriteTools 按簇搬出，函数体逐字一致；token/fail 由 AgentToolHost 提供。
internal object AgentGroupWriteTools {
        internal fun ids(raw: String): List<String> =
            raw.split(',', ' ', ';', '\n').map { it.trim() }.filter { it.isNotBlank() }.distinct()

        internal suspend fun createDirect(app: MaodouchatApp, userId: String): String {
            if (userId.isBlank()) return "Error: userId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val chat = ApiService.createChat(token, listOf(userId), isGroup = false).getOrElse { return AgentToolHost.fail(it) }
            return "Direct chat ${chat.id}"
        }

        internal suspend fun createGroup(app: MaodouchatApp, name: String, memberIds: String): String {
            val groupName = name.trim().take(50)
            val members = ids(memberIds)
            if (groupName.isBlank() || members.isEmpty()) return "Error: name and memberIds required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val chat = ApiService.createChat(token, members, isGroup = true, groupName = groupName).getOrElse { return AgentToolHost.fail(it) }
            return "Group ${chat.id} name=${chat.groupName.orEmpty()}"
        }

        internal suspend fun renameGroup(app: MaodouchatApp, chatId: String, name: String): String {
            val groupName = name.trim().take(50)
            if (chatId.isBlank() || groupName.isBlank()) return "Error: chatId and name required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            ApiService.renameGroup(token, chatId, groupName).getOrElse { return AgentToolHost.fail(it) }
            return "Renamed $chatId to $groupName"
        }

        internal suspend fun updateAnnouncement(app: MaodouchatApp, chatId: String, announcement: String): String {
            if (chatId.isBlank()) return "Error: chatId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            ApiService.updateGroupAnnouncement(token, chatId, announcement.take(1_200)).getOrElse { return AgentToolHost.fail(it) }
            return "Updated announcement for $chatId"
        }

        internal suspend fun addMembers(app: MaodouchatApp, chatId: String, memberIds: String): String {
            val members = ids(memberIds)
            if (chatId.isBlank() || members.isEmpty()) return "Error: chatId and memberIds required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            ApiService.addGroupMembers(token, chatId, members).getOrElse { return AgentToolHost.fail(it) }
            return "Added ${members.size} members to $chatId"
        }

        internal suspend fun removeMember(app: MaodouchatApp, chatId: String, memberId: String): String {
            if (chatId.isBlank() || memberId.isBlank()) return "Error: chatId and memberId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            ApiService.removeGroupMember(token, chatId, memberId).getOrElse { return AgentToolHost.fail(it) }
            return "Removed $memberId from $chatId"
        }

        internal suspend fun muteMember(app: MaodouchatApp, chatId: String, memberId: String, mutedUntil: Long): String {
            if (chatId.isBlank() || memberId.isBlank()) return "Error: chatId and memberId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            ApiService.updateMemberMute(token, chatId, memberId, mutedUntil.coerceAtLeast(0L)).getOrElse { return AgentToolHost.fail(it) }
            return "Muted $memberId until $mutedUntil"
        }

        internal suspend fun deleteChat(app: MaodouchatApp, chatId: String): String {
            AgentMessagingReadTools.denySecretOrLockedChat(app, chatId)?.let { return it }
            if (chatId.isBlank()) return "Error: chatId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            ApiService.deleteChat(token, chatId).getOrElse { return AgentToolHost.fail(it) }
            ChatRepository(app.database.chatDao(), app.database.userDao()).deleteChat(chatId)
            return "Deleted chat $chatId"
        }
}
