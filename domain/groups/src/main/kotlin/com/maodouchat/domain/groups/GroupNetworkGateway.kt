package com.maodouchat.domain.groups

import com.maodouchat.core.model.ChatDto
import com.maodouchat.core.model.GroupAuditLogDto
import com.maodouchat.core.model.GroupInviteResponse
import com.maodouchat.core.model.GroupMemberDto
import com.maodouchat.core.model.SenderKeyDistributionStatusDto
import com.maodouchat.core.model.UserDto

/**
 * 群域的网络端口：群生命周期服务与群控制器用到的全部远端调用。
 * 方法签名与 :core:network 的分域契约（GroupApi / SocialApi / MediaApi /
 * ConversationApi）逐一对应；app 侧由 AndroidGroupWiring 绑定到 ApiService。
 */
interface GroupNetworkGateway {
    suspend fun addGroupMembers(token: String, chatId: String, memberIds: List<String>): Result<Unit>
    suspend fun removeGroupMember(token: String, chatId: String, memberId: String): Result<Unit>
    suspend fun updateMemberRole(token: String, chatId: String, memberId: String, role: String): Result<Unit>
    suspend fun transferGroupOwnership(token: String, chatId: String, memberId: String): Result<Unit>
    suspend fun updateMemberTitle(token: String, chatId: String, memberId: String, title: String): Result<Unit>
    suspend fun updateMemberMute(token: String, chatId: String, memberId: String, mutedUntil: Long): Result<Unit>
    suspend fun muteAllMembers(token: String, chatId: String, mutedUntil: Long): Result<Unit>
    suspend fun renameGroup(token: String, chatId: String, newName: String): Result<Unit>
    suspend fun updateGroupAnnouncement(token: String, chatId: String, announcement: String): Result<Unit>
    suspend fun uploadGroupAvatar(token: String, chatId: String, base64Data: String): Result<String>
    suspend fun updateGroupNickname(token: String, chatId: String, nickname: String): Result<Unit>
    suspend fun deleteChat(token: String, chatId: String): Result<Unit>
    suspend fun getChats(token: String): Result<List<ChatDto>>
    suspend fun getGroupMembers(token: String, chatId: String): Result<List<GroupMemberDto>>
    suspend fun getAllSearchableUsers(token: String): Result<List<UserDto>>
    suspend fun getOrCreateGroupInvite(
        token: String,
        chatId: String,
        rotate: Boolean = false,
        expiresInSeconds: Long = 7L * 24L * 60L * 60L,
        maxUses: Int = 100,
    ): Result<GroupInviteResponse>
    suspend fun getGroupAudit(token: String, chatId: String, limit: Int = 50, offset: Int = 0): Result<List<GroupAuditLogDto>>
    suspend fun listBots(token: String): Result<String>
    suspend fun inviteBotToChat(token: String, chatId: String, botId: String): Result<String>
    suspend fun getSenderKeyDistributionStatus(
        token: String,
        chatId: String,
        epoch: Long? = null,
        currentDeviceId: Int? = null,
    ): Result<SenderKeyDistributionStatusDto>
}
