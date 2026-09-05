package com.maodouchat.group

import com.maodouchat.network.GroupMemberDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

data class GroupMemberUi(
    val userId: String,
    val name: String,
    val avatar: String? = null,
    val role: String = "MEMBER",
    val title: String? = null,
    val groupNickname: String? = null,
    val joinedAt: Long = 0,
    val isOnline: Boolean = false,
    val mutedUntil: Long = 0
) {
    val displayName: String get() = groupNickname?.takeIf { it.isNotBlank() } ?: name
    val isMuted: Boolean get() = mutedUntil > System.currentTimeMillis()
}

fun GroupMemberDto.toUi(): GroupMemberUi = GroupMemberUi(
    userId = userId,
    name = name,
    avatar = avatar,
    role = role,
    title = title,
    groupNickname = groupNickname,
    joinedAt = joinedAt,
    isOnline = isOnline,
    mutedUntil = mutedUntil
)

data class GroupMembershipSnapshot(
    val chatId: String,
    val memberRevision: Long,
    val members: List<GroupMemberUi>,
    val myRole: String = "MEMBER",
    val myNickname: String = "",
    val groupName: String = "",
    val groupAnnouncement: String = "",
    val groupAvatar: String? = null,
    val isChannel: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 客户端群成员快照与 Revision 存储（U06）。
 * 保存本地快照与 revision，支持跨页面与后台事件状态共享与观测。
 */
interface GroupMembershipStore {
    fun snapshotFlow(chatId: String): StateFlow<GroupMembershipSnapshot?>
    fun getSnapshot(chatId: String): GroupMembershipSnapshot?
    fun updateSnapshot(chatId: String, snapshot: GroupMembershipSnapshot)
    fun updateMembers(chatId: String, members: List<GroupMemberUi>, revision: Long? = null)
    fun updateMemberOnlineStatus(chatId: String, userId: String, isOnline: Boolean)
    fun invalidate(chatId: String)
    fun clear()
}

class InMemoryGroupMembershipStore : GroupMembershipStore {
    private val flows = ConcurrentHashMap<String, MutableStateFlow<GroupMembershipSnapshot?>>()

    override fun snapshotFlow(chatId: String): StateFlow<GroupMembershipSnapshot?> {
        return flows.getOrPut(chatId) { MutableStateFlow(null) }.asStateFlow()
    }

    override fun getSnapshot(chatId: String): GroupMembershipSnapshot? {
        return flows[chatId]?.value
    }

    override fun updateSnapshot(chatId: String, snapshot: GroupMembershipSnapshot) {
        flows.getOrPut(chatId) { MutableStateFlow(null) }.value = snapshot
    }

    override fun updateMembers(chatId: String, members: List<GroupMemberUi>, revision: Long?) {
        val flow = flows.getOrPut(chatId) { MutableStateFlow(null) }
        flow.update { current ->
            current?.copy(
                members = members,
                memberRevision = revision ?: current.memberRevision,
                updatedAt = System.currentTimeMillis()
            ) ?: GroupMembershipSnapshot(
                chatId = chatId,
                memberRevision = revision ?: 0L,
                members = members
            )
        }
    }

    override fun updateMemberOnlineStatus(chatId: String, userId: String, isOnline: Boolean) {
        val flow = flows[chatId] ?: return
        flow.update { current ->
            if (current == null) return@update null
            current.copy(
                members = current.members.map { member ->
                    if (member.userId == userId) member.copy(isOnline = isOnline) else member
                }
            )
        }
    }

    override fun invalidate(chatId: String) {
        flows[chatId]?.value = null
    }

    override fun clear() {
        flows.clear()
    }
}
