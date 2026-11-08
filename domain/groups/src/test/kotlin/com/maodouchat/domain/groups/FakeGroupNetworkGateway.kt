package com.maodouchat.domain.groups

import com.maodouchat.core.model.ChatDto
import com.maodouchat.core.model.GroupAuditLogDto
import com.maodouchat.core.model.GroupInviteResponse
import com.maodouchat.core.model.GroupMemberDto
import com.maodouchat.core.model.SenderKeyDistributionStatusDto
import com.maodouchat.core.model.UserDto

/** 测试替身：未设置的调用直接报错，逼测试显式声明它依赖的端口行为。 */
internal class FakeGroupNetworkGateway : GroupNetworkGateway {
    var addGroupMembers: (suspend (String, String, List<String>) -> Result<Unit>)? = null
    var removeGroupMember: (suspend (String, String, String) -> Result<Unit>)? = null
    var updateMemberRole: (suspend (String, String, String, String) -> Result<Unit>)? = null
    var transferGroupOwnership: (suspend (String, String, String) -> Result<Unit>)? = null
    var updateMemberTitle: (suspend (String, String, String, String) -> Result<Unit>)? = null
    var updateMemberMute: (suspend (String, String, String, Long) -> Result<Unit>)? = null
    var muteAllMembers: (suspend (String, String, Long) -> Result<Unit>)? = null
    var renameGroup: (suspend (String, String, String) -> Result<Unit>)? = null
    var updateGroupAnnouncement: (suspend (String, String, String) -> Result<Unit>)? = null
    var uploadGroupAvatar: (suspend (String, String, String) -> Result<String>)? = null
    var updateGroupNickname: (suspend (String, String, String) -> Result<Unit>)? = null
    var deleteChat: (suspend (String, String) -> Result<Unit>)? = null
    var getChats: (suspend (String) -> Result<List<ChatDto>>)? = null
    var getGroupMembers: (suspend (String, String) -> Result<List<GroupMemberDto>>)? = null
    var getAllSearchableUsers: (suspend (String) -> Result<List<UserDto>>)? = null
    var getOrCreateGroupInvite: (suspend (String, String, Boolean, Long, Int) -> Result<GroupInviteResponse>)? = null
    var getGroupAudit: (suspend (String, String, Int, Int) -> Result<List<GroupAuditLogDto>>)? = null
    var listBots: (suspend (String) -> Result<String>)? = null
    var inviteBotToChat: (suspend (String, String, String) -> Result<String>)? = null
    var getSenderKeyDistributionStatus: (suspend (String, String, Long?, Int?) -> Result<SenderKeyDistributionStatusDto>)? = null

    private fun <T> unexpected(name: String): T = error("unexpected gateway call: $name")

    override suspend fun addGroupMembers(token: String, chatId: String, memberIds: List<String>): Result<Unit> =
        addGroupMembers?.invoke(token, chatId, memberIds) ?: unexpected("addGroupMembers")

    override suspend fun removeGroupMember(token: String, chatId: String, memberId: String): Result<Unit> =
        removeGroupMember?.invoke(token, chatId, memberId) ?: unexpected("removeGroupMember")

    override suspend fun updateMemberRole(token: String, chatId: String, memberId: String, role: String): Result<Unit> =
        updateMemberRole?.invoke(token, chatId, memberId, role) ?: unexpected("updateMemberRole")

    override suspend fun transferGroupOwnership(token: String, chatId: String, memberId: String): Result<Unit> =
        transferGroupOwnership?.invoke(token, chatId, memberId) ?: unexpected("transferGroupOwnership")

    override suspend fun updateMemberTitle(token: String, chatId: String, memberId: String, title: String): Result<Unit> =
        updateMemberTitle?.invoke(token, chatId, memberId, title) ?: unexpected("updateMemberTitle")

    override suspend fun updateMemberMute(token: String, chatId: String, memberId: String, mutedUntil: Long): Result<Unit> =
        updateMemberMute?.invoke(token, chatId, memberId, mutedUntil) ?: unexpected("updateMemberMute")

    override suspend fun muteAllMembers(token: String, chatId: String, mutedUntil: Long): Result<Unit> =
        muteAllMembers?.invoke(token, chatId, mutedUntil) ?: unexpected("muteAllMembers")

    override suspend fun renameGroup(token: String, chatId: String, newName: String): Result<Unit> =
        renameGroup?.invoke(token, chatId, newName) ?: unexpected("renameGroup")

    override suspend fun updateGroupAnnouncement(token: String, chatId: String, announcement: String): Result<Unit> =
        updateGroupAnnouncement?.invoke(token, chatId, announcement) ?: unexpected("updateGroupAnnouncement")

    override suspend fun uploadGroupAvatar(token: String, chatId: String, base64Data: String): Result<String> =
        uploadGroupAvatar?.invoke(token, chatId, base64Data) ?: unexpected("uploadGroupAvatar")

    override suspend fun updateGroupNickname(token: String, chatId: String, nickname: String): Result<Unit> =
        updateGroupNickname?.invoke(token, chatId, nickname) ?: unexpected("updateGroupNickname")

    override suspend fun deleteChat(token: String, chatId: String): Result<Unit> =
        deleteChat?.invoke(token, chatId) ?: unexpected("deleteChat")

    override suspend fun getChats(token: String): Result<List<ChatDto>> =
        getChats?.invoke(token) ?: unexpected("getChats")

    override suspend fun getGroupMembers(token: String, chatId: String): Result<List<GroupMemberDto>> =
        getGroupMembers?.invoke(token, chatId) ?: unexpected("getGroupMembers")

    override suspend fun getAllSearchableUsers(token: String): Result<List<UserDto>> =
        getAllSearchableUsers?.invoke(token) ?: unexpected("getAllSearchableUsers")

    override suspend fun getOrCreateGroupInvite(
        token: String,
        chatId: String,
        rotate: Boolean,
        expiresInSeconds: Long,
        maxUses: Int,
    ): Result<GroupInviteResponse> =
        getOrCreateGroupInvite?.invoke(token, chatId, rotate, expiresInSeconds, maxUses)
            ?: unexpected("getOrCreateGroupInvite")

    override suspend fun getGroupAudit(token: String, chatId: String, limit: Int, offset: Int): Result<List<GroupAuditLogDto>> =
        getGroupAudit?.invoke(token, chatId, limit, offset) ?: unexpected("getGroupAudit")

    override suspend fun listBots(token: String): Result<String> =
        listBots?.invoke(token) ?: unexpected("listBots")

    override suspend fun inviteBotToChat(token: String, chatId: String, botId: String): Result<String> =
        inviteBotToChat?.invoke(token, chatId, botId) ?: unexpected("inviteBotToChat")

    override suspend fun getSenderKeyDistributionStatus(
        token: String,
        chatId: String,
        epoch: Long?,
        currentDeviceId: Int?,
    ): Result<SenderKeyDistributionStatusDto> =
        getSenderKeyDistributionStatus?.invoke(token, chatId, epoch, currentDeviceId)
            ?: unexpected("getSenderKeyDistributionStatus")
}
