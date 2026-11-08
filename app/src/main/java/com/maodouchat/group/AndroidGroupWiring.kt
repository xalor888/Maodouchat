package com.maodouchat.group

import com.maodouchat.core.model.GroupLifecycleCoordinator
import com.maodouchat.core.model.GroupLifecycleService
import com.maodouchat.core.model.GroupMembershipStore
import com.maodouchat.core.model.GroupSenderKeyMaintenanceCoordinator
import com.maodouchat.domain.groups.DefaultGroupLifecycleService
import com.maodouchat.domain.groups.GroupAuditController
import com.maodouchat.domain.groups.GroupBotController
import com.maodouchat.domain.groups.GroupEncryptionHealthController
import com.maodouchat.domain.groups.GroupInviteController
import com.maodouchat.domain.groups.GroupNetworkGateway
import com.maodouchat.network.ApiService
import com.maodouchat.session.CurrentSession

/** app 侧装配：群生命周期服务与群控制器的网络调用与会话 token，UI 层只拿成品。 */
internal fun createAndroidGroupLifecycleService(
    coordinator: GroupLifecycleCoordinator,
    membershipStore: GroupMembershipStore? = null,
): GroupLifecycleService = DefaultGroupLifecycleService(
    coordinator = coordinator,
    tokenProvider = { CurrentSession.snapshot().token.orEmpty() },
    membershipStore = membershipStore,
    gateway = AndroidGroupNetworkGateway,
)

internal fun createAndroidGroupInviteController(): GroupInviteController =
    GroupInviteController(
        tokenProvider = { CurrentSession.snapshot().token.orEmpty() },
        gateway = AndroidGroupNetworkGateway,
    )

internal fun createAndroidGroupAuditController(): GroupAuditController =
    GroupAuditController(
        tokenProvider = { CurrentSession.snapshot().token.orEmpty() },
        gateway = AndroidGroupNetworkGateway,
    )

internal fun createAndroidGroupBotController(): GroupBotController =
    GroupBotController(
        tokenProvider = { CurrentSession.snapshot().token.orEmpty() },
        gateway = AndroidGroupNetworkGateway,
    )

internal fun createAndroidGroupEncryptionHealthController(
    maintenanceCoordinator: GroupSenderKeyMaintenanceCoordinator,
): GroupEncryptionHealthController = GroupEncryptionHealthController(
    maintenanceCoordinator = maintenanceCoordinator,
    tokenProvider = { CurrentSession.snapshot().token.orEmpty() },
    gateway = AndroidGroupNetworkGateway,
)

/** 群域端口的唯一 app 侧实现：逐方法委托给 ApiService（分域契约已逐一对应）。 */
internal object AndroidGroupNetworkGateway : GroupNetworkGateway {
    override suspend fun addGroupMembers(token: String, chatId: String, memberIds: List<String>) =
        ApiService.addGroupMembers(token, chatId, memberIds)

    override suspend fun removeGroupMember(token: String, chatId: String, memberId: String) =
        ApiService.removeGroupMember(token, chatId, memberId)

    override suspend fun updateMemberRole(token: String, chatId: String, memberId: String, role: String) =
        ApiService.updateMemberRole(token, chatId, memberId, role)

    override suspend fun transferGroupOwnership(token: String, chatId: String, memberId: String) =
        ApiService.transferGroupOwnership(token, chatId, memberId)

    override suspend fun updateMemberTitle(token: String, chatId: String, memberId: String, title: String) =
        ApiService.updateMemberTitle(token, chatId, memberId, title)

    override suspend fun updateMemberMute(token: String, chatId: String, memberId: String, mutedUntil: Long) =
        ApiService.updateMemberMute(token, chatId, memberId, mutedUntil)

    override suspend fun muteAllMembers(token: String, chatId: String, mutedUntil: Long) =
        ApiService.muteAllMembers(token, chatId, mutedUntil)

    override suspend fun renameGroup(token: String, chatId: String, newName: String) =
        ApiService.renameGroup(token, chatId, newName)

    override suspend fun updateGroupAnnouncement(token: String, chatId: String, announcement: String) =
        ApiService.updateGroupAnnouncement(token, chatId, announcement)

    override suspend fun uploadGroupAvatar(token: String, chatId: String, base64Data: String) =
        ApiService.uploadGroupAvatar(token, chatId, base64Data)

    override suspend fun updateGroupNickname(token: String, chatId: String, nickname: String) =
        ApiService.updateGroupNickname(token, chatId, nickname)

    override suspend fun deleteChat(token: String, chatId: String) =
        ApiService.deleteChat(token, chatId)

    override suspend fun getChats(token: String) = ApiService.getChats(token)

    override suspend fun getGroupMembers(token: String, chatId: String) =
        ApiService.getGroupMembers(token, chatId)

    override suspend fun getAllSearchableUsers(token: String) =
        ApiService.getAllSearchableUsers(token)

    override suspend fun getOrCreateGroupInvite(
        token: String,
        chatId: String,
        rotate: Boolean,
        expiresInSeconds: Long,
        maxUses: Int,
    ) = ApiService.getOrCreateGroupInvite(token, chatId, rotate, expiresInSeconds, maxUses)

    override suspend fun getGroupAudit(token: String, chatId: String, limit: Int, offset: Int) =
        ApiService.getGroupAudit(token, chatId, limit, offset)

    override suspend fun listBots(token: String) = ApiService.listBots(token)

    override suspend fun inviteBotToChat(token: String, chatId: String, botId: String) =
        ApiService.inviteBotToChat(token, chatId, botId)

    override suspend fun getSenderKeyDistributionStatus(
        token: String,
        chatId: String,
        epoch: Long?,
        currentDeviceId: Int?,
    ) = ApiService.getSenderKeyDistributionStatus(token, chatId, epoch, currentDeviceId)
}
