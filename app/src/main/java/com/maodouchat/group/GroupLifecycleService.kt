package com.maodouchat.group

import com.maodouchat.data.model.User
import com.maodouchat.network.ChatDto
import com.maodouchat.network.GroupMemberDto
import com.maodouchat.ui.screen.chatdetail.GroupMutationCommit

/**
 * 客户端群生命周期服务（U06）。
 * 作为客户端成员、角色、所有权、资料 mutation 与数据获取的唯一入口。
 * 所有变更返回 [GroupMutationCommit]，保证 mutation 本身成功即完成提交，
 * 刷新与 Sender Key 修复作为 post-commit best-effort 处理，不回滚已提交的成功操作。
 */
interface GroupLifecycleService {
    suspend fun addMembers(chatId: String, userIds: List<String>): GroupMutationCommit
    suspend fun removeMember(chatId: String, userId: String): GroupMutationCommit
    suspend fun setRole(chatId: String, userId: String, role: String): GroupMutationCommit
    suspend fun transferOwnership(chatId: String, newOwnerUserId: String): GroupMutationCommit
    suspend fun setTitle(chatId: String, userId: String, title: String): GroupMutationCommit
    suspend fun setMemberMute(chatId: String, userId: String, mutedUntil: Long): GroupMutationCommit
    suspend fun setMuteAll(chatId: String, muted: Boolean): GroupMutationCommit
    suspend fun updateGroupInfo(chatId: String, name: String?, announcement: String?, avatar: String?): GroupMutationCommit
    suspend fun updateMyNickname(chatId: String, nickname: String): GroupMutationCommit
    suspend fun leaveGroup(chatId: String): GroupMutationCommit
    suspend fun dissolveGroup(chatId: String): GroupMutationCommit

    suspend fun fetchGroupDetails(chatId: String): Result<ChatDto?>
    suspend fun fetchGroupMembers(chatId: String): Result<List<GroupMemberDto>>
    suspend fun fetchCandidates(excludeMemberIds: Set<String>, excludeUserId: String): Result<List<User>>
    suspend fun uploadAvatar(chatId: String, base64: String): Result<String>
}
