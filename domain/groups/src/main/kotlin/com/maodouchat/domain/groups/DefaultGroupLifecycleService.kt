package com.maodouchat.domain.groups

import com.maodouchat.core.model.GroupMemberDto
import com.maodouchat.core.model.toUi
import com.maodouchat.core.model.User
import com.maodouchat.core.model.ChatDto
import com.maodouchat.core.model.GroupLifecycleCoordinator
import com.maodouchat.core.model.GroupMembershipStore
import com.maodouchat.core.model.GroupMutationCommit
import com.maodouchat.core.model.GroupLifecycleService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 默认群生命周期服务实现（U06）。
 * 包装 [GroupLifecycleCoordinator]，集中执行所有群组网络变更与 Sender Key 轮换策略，
 * 并同步更新 [GroupMembershipStore] 本地快照。
 */
class DefaultGroupLifecycleService(
    private val coordinator: GroupLifecycleCoordinator,
    private val tokenProvider: () -> String,
    private val membershipStore: GroupMembershipStore? = null,
    private val gateway: GroupNetworkGateway,
) : GroupLifecycleService {

    override suspend fun addMembers(chatId: String, userIds: List<String>): GroupMutationCommit {
        val commit = coordinator.mutate(chatId, rotateSenderKey = true) { token ->
            gateway.addGroupMembers(token, chatId, userIds).getOrThrow()
        }
        applyCommitToStore(chatId, commit)
        return commit
    }

    override suspend fun removeMember(chatId: String, userId: String): GroupMutationCommit {
        val commit = coordinator.mutate(chatId, rotateSenderKey = true) { token ->
            gateway.removeGroupMember(token, chatId, userId).getOrThrow()
        }
        applyCommitToStore(chatId, commit)
        return commit
    }

    override suspend fun setRole(chatId: String, userId: String, role: String): GroupMutationCommit {
        val commit = coordinator.mutate(chatId, rotateSenderKey = false) { token ->
            gateway.updateMemberRole(token, chatId, userId, role).getOrThrow()
        }
        applyCommitToStore(chatId, commit)
        return commit
    }

    override suspend fun transferOwnership(chatId: String, newOwnerUserId: String): GroupMutationCommit {
        val commit = coordinator.mutate(chatId, rotateSenderKey = false) { token ->
            gateway.transferGroupOwnership(token, chatId, newOwnerUserId).getOrThrow()
        }
        applyCommitToStore(chatId, commit)
        return commit
    }

    override suspend fun setTitle(chatId: String, userId: String, title: String): GroupMutationCommit {
        val commit = coordinator.mutate(chatId, rotateSenderKey = false) { token ->
            gateway.updateMemberTitle(token, chatId, userId, title).getOrThrow()
        }
        applyCommitToStore(chatId, commit)
        return commit
    }

    override suspend fun setMemberMute(chatId: String, userId: String, mutedUntil: Long): GroupMutationCommit {
        val commit = coordinator.mutate(chatId, rotateSenderKey = false) { token ->
            gateway.updateMemberMute(token, chatId, userId, mutedUntil).getOrThrow()
        }
        applyCommitToStore(chatId, commit)
        return commit
    }

    override suspend fun setMuteAll(chatId: String, muted: Boolean): GroupMutationCommit {
        val muteUntil = if (muted) System.currentTimeMillis() + 86400000L * 365 else 0L
        val commit = coordinator.mutate(chatId, rotateSenderKey = false) { token ->
            gateway.muteAllMembers(token, chatId, muteUntil).getOrThrow()
        }
        applyCommitToStore(chatId, commit)
        return commit
    }

    override suspend fun updateGroupInfo(chatId: String, name: String?, announcement: String?, avatar: String?): GroupMutationCommit {
        val commit = coordinator.mutate(chatId, rotateSenderKey = false) { token ->
            if (!name.isNullOrBlank()) {
                gateway.renameGroup(token, chatId, name.trim()).getOrThrow()
            }
            if (announcement != null) {
                gateway.updateGroupAnnouncement(token, chatId, announcement.trim()).getOrThrow()
            }
            if (!avatar.isNullOrBlank()) {
                gateway.uploadGroupAvatar(token, chatId, avatar).getOrThrow()
            }
        }
        applyCommitToStore(chatId, commit)
        return commit
    }

    override suspend fun updateMyNickname(chatId: String, nickname: String): GroupMutationCommit {
        val commit = coordinator.mutate(chatId, rotateSenderKey = false) { token ->
            gateway.updateGroupNickname(token, chatId, nickname.trim()).getOrThrow()
        }
        applyCommitToStore(chatId, commit)
        return commit
    }

    override suspend fun leaveGroup(chatId: String): GroupMutationCommit {
        val commit = coordinator.mutate(chatId, rotateSenderKey = true) { token ->
            gateway.deleteChat(token, chatId).getOrThrow()
        }
        membershipStore?.invalidate(chatId)
        return commit
    }

    override suspend fun dissolveGroup(chatId: String): GroupMutationCommit {
        val commit = coordinator.mutate(chatId, rotateSenderKey = false) { token ->
            gateway.deleteChat(token, chatId).getOrThrow()
        }
        membershipStore?.invalidate(chatId)
        return commit
    }

    override suspend fun fetchGroupDetails(chatId: String): Result<ChatDto?> = withContext(Dispatchers.IO) {
        val token = tokenProvider().trim()
        if (token.isBlank()) return@withContext Result.failure(IllegalStateException("session_missing"))
        gateway.getChats(token).map { chats ->
            chats.firstOrNull { it.id == chatId }
        }
    }

    override suspend fun fetchGroupMembers(chatId: String): Result<List<GroupMemberDto>> = withContext(Dispatchers.IO) {
        val token = tokenProvider().trim()
        if (token.isBlank()) return@withContext Result.failure(IllegalStateException("session_missing"))
        val result = gateway.getGroupMembers(token, chatId)
        result.onSuccess { members ->
            membershipStore?.updateMembers(chatId, members.map { it.toUi() })
        }
        result
    }

    override suspend fun fetchCandidates(excludeMemberIds: Set<String>, excludeUserId: String): Result<List<User>> = withContext(Dispatchers.IO) {
        val token = tokenProvider().trim()
        if (token.isBlank()) return@withContext Result.failure(IllegalStateException("session_missing"))
        gateway.getAllSearchableUsers(token).map { users ->
            users
                .filter { it.id !in excludeMemberIds && it.id != excludeUserId }
                .map { User(it.id, it.name, it.avatar, it.email, it.isOnline, it.status) }
        }
    }

    override suspend fun uploadAvatar(chatId: String, base64: String): Result<String> = withContext(Dispatchers.IO) {
        val token = tokenProvider().trim()
        if (token.isBlank()) return@withContext Result.failure(IllegalStateException("session_missing"))
        gateway.uploadGroupAvatar(token, chatId, base64)
    }

    private fun applyCommitToStore(chatId: String, commit: GroupMutationCommit) {
        val chat = commit.refreshedChat ?: return
        membershipStore?.let { store ->
            val existing = store.getSnapshot(chatId)
            if (existing != null) {
                store.updateSnapshot(
                    chatId,
                    existing.copy(
                        memberRevision = chat.memberRevision,
                        groupName = chat.groupName?.ifBlank { existing.groupName } ?: existing.groupName,
                        groupAnnouncement = chat.groupAnnouncement ?: existing.groupAnnouncement,
                        groupAvatar = chat.groupAvatar ?: existing.groupAvatar,
                        isChannel = chat.isChannel,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }
}
