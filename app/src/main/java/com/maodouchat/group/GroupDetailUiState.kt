package com.maodouchat.group

/**
 * 群详情页界面状态（从 `ui/screen/chatdetail/GroupDetailViewModel.kt` 抽出）。
 * 与该 ViewModel 的其它 1000 行逻辑分开：状态模型是纯数据，改它不需要读懂整个 VM。
 */
import com.maodouchat.data.model.User
import com.maodouchat.network.SenderKeyDistributionStatusDto
import com.maodouchat.group.GroupOwnedBotUi
import com.maodouchat.group.GroupMemberUi
import com.maodouchat.network.GroupAuditLogDto

/** 群机器人条目在 UI 侧的短别名（`GroupOwnedBotUi` 的同名引用点较多，保留别名以免大面积改签名）。 */
typealias OwnedBotUi = GroupOwnedBotUi

data class GroupDetailUiState(
    val groupName: String = "",
    val groupAnnouncement: String = "",
    val groupAvatar: String? = null,
    val memberRevision: Long = 0,
    val members: List<GroupMemberUi> = emptyList(),
    val candidates: List<User> = emptyList(),
    val senderKeyStatus: SenderKeyDistributionStatusDto? = null,
    /** 8.48：本机是否实际持有当前 epoch 的 Sender Key（区别于服务端分发记录）。 */
    val localHasSenderKey: Boolean? = null,
    val groupInvitePayload: String = "",
    val inviteExpiresAt: Long = 0,
    val inviteMaxUses: Int = 0,
    val inviteUsedCount: Int = 0,
    val inviteRemainingUses: Int = 0,
    val auditLogs: List<GroupAuditLogDto> = emptyList(),
    val isLoadingMoreAudit: Boolean = false,
    val hasMoreAudit: Boolean = false,
    val currentUserId: String = "",
    val myRole: String = "MEMBER",
    val myNickname: String = "",
    val isLoading: Boolean = true,
    val isUpdating: Boolean = false,
    val isLoadingInvite: Boolean = false,
    val isUploadingAvatar: Boolean = false,
    val message: String? = null,
    /** Structured feedback for transfer/mute/invite/avatar failures — enables retry without swallowing errors. */
    val feedback: GroupMutationFeedback? = null,
    val isSecretChat: Boolean = false,
    /** 广播频道（单向一对多）：非 OWNER 订阅者只读。 */
    val isChannel: Boolean = false,
    val ownedBots: List<GroupOwnedBotUi> = emptyList(),
    val isInvitingBot: Boolean = false,
) {
    val canManageGroup: Boolean get() = myRole == "OWNER" || myRole == "ADMIN"
    val isOwner: Boolean get() = myRole == "OWNER"
}

