package com.maodouchat.ai.agent

import com.maodouchat.data.repository.ChatRepository
import com.maodouchat.domain.groups.GroupNamePolicy
import com.maodouchat.domain.messaging.AgentGroupResultPolicy
import com.maodouchat.domain.messaging.AgentToolInputPolicy
import com.maodouchat.domain.messaging.AgentWriteInputPolicy
import com.maodouchat.domain.messaging.AgentWriteErrorPolicy
import com.maodouchat.network.ApiService

// Agent 工具执行簇：群组/会话写操作（创建单聊与群/改名/公告/成员管理/解散）。
// 从 AgentMessagingWriteTools 按簇搬出，函数体逐字一致；token/fail 由 AgentToolHost 提供。
internal object AgentGroupWriteTools {
        internal suspend fun createDirect(ports: AgentToolPorts, userId: String): String {
            if (userId.isBlank()) return AgentWriteErrorPolicy.required("userId")
            val token = ports.token ?: return AgentWriteErrorPolicy.notSignedIn()
            val chat = ApiService.createChat(token, listOf(userId), isGroup = false).getOrElse { return AgentToolHost.fail(it) }
            return AgentGroupResultPolicy.directChat(chat.id)
        }

        internal suspend fun createGroup(ports: AgentToolPorts, name: String, memberIds: String): String {
            val groupName = GroupNamePolicy.normalize(name)
            val members = AgentWriteInputPolicy.parseMemberIds(memberIds)
            if (groupName.isBlank() || members.isEmpty()) return AgentWriteErrorPolicy.required("name", "memberIds")
            val token = ports.token ?: return AgentWriteErrorPolicy.notSignedIn()
            val chat = ApiService.createChat(token, members, isGroup = true, groupName = groupName).getOrElse { return AgentToolHost.fail(it) }
            return AgentGroupResultPolicy.group(chat.id, chat.groupName.orEmpty())
        }

        internal suspend fun renameGroup(ports: AgentToolPorts, chatId: String, name: String): String {
            val groupName = GroupNamePolicy.normalize(name)
            if (chatId.isBlank() || groupName.isBlank()) return AgentWriteErrorPolicy.required("chatId", "name")
            val token = ports.token ?: return AgentWriteErrorPolicy.notSignedIn()
            ApiService.renameGroup(token, chatId, groupName).getOrElse { return AgentToolHost.fail(it) }
            return AgentGroupResultPolicy.renamed(chatId, groupName)
        }

        internal suspend fun updateAnnouncement(ports: AgentToolPorts, chatId: String, announcement: String): String {
            if (chatId.isBlank()) return AgentWriteErrorPolicy.required("chatId")
            val token = ports.token ?: return AgentWriteErrorPolicy.notSignedIn()
            ApiService.updateGroupAnnouncement(token, chatId, AgentToolInputPolicy.cleanAnnouncement(announcement)).getOrElse { return AgentToolHost.fail(it) }
            return AgentGroupResultPolicy.announcementUpdated(chatId)
        }

        internal suspend fun addMembers(ports: AgentToolPorts, chatId: String, memberIds: String): String {
            val members = AgentWriteInputPolicy.parseMemberIds(memberIds)
            if (chatId.isBlank() || members.isEmpty()) return AgentWriteErrorPolicy.required("chatId", "memberIds")
            val token = ports.token ?: return AgentWriteErrorPolicy.notSignedIn()
            ApiService.addGroupMembers(token, chatId, members).getOrElse { return AgentToolHost.fail(it) }
            return AgentGroupResultPolicy.membersAdded(members.size, chatId)
        }

        internal suspend fun removeMember(ports: AgentToolPorts, chatId: String, memberId: String): String {
            if (chatId.isBlank() || memberId.isBlank()) return AgentWriteErrorPolicy.required("chatId", "memberId")
            val token = ports.token ?: return AgentWriteErrorPolicy.notSignedIn()
            ApiService.removeGroupMember(token, chatId, memberId).getOrElse { return AgentToolHost.fail(it) }
            return AgentGroupResultPolicy.memberRemoved(memberId, chatId)
        }

        internal suspend fun muteMember(ports: AgentToolPorts, chatId: String, memberId: String, mutedUntil: Long): String {
            if (chatId.isBlank() || memberId.isBlank()) return AgentWriteErrorPolicy.required("chatId", "memberId")
            val token = ports.token ?: return AgentWriteErrorPolicy.notSignedIn()
            ApiService.updateMemberMute(token, chatId, memberId, AgentWriteInputPolicy.clampMuteUntil(mutedUntil)).getOrElse { return AgentToolHost.fail(it) }
            return AgentGroupResultPolicy.muted(memberId, mutedUntil)
        }

        internal suspend fun deleteChat(ports: AgentToolPorts, chatId: String): String {
            AgentMessagingReadGates.denySecretOrLockedChat(ports, chatId)?.let { return it }
            if (chatId.isBlank()) return AgentWriteErrorPolicy.required("chatId")
            val token = ports.token ?: return AgentWriteErrorPolicy.notSignedIn()
            ApiService.deleteChat(token, chatId).getOrElse { return AgentToolHost.fail(it) }
            ChatRepository(ports.database.chatDao(), ports.database.userDao()).deleteChat(chatId)
            return AgentGroupResultPolicy.chatDeleted(chatId)
        }
}
