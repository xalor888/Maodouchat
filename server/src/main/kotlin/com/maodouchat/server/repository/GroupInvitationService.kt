package com.maodouchat.server.repository

import com.maodouchat.server.model.GroupInvitationDto

/**
 * B05 子项 2：群邀请创建、轮换、接受、拒绝、撤销与过期的统一入口（domain facade）。
 *
 * 邀请状态机（含 invite-link token）全部经由本服务收敛，底层 SQL 边界在
 * [GroupInvitationRepository]。接受/链接加入产生的成员新增与 revision 递增由仓库内
 * 原子完成，本层保持薄委托以与 GroupMembershipService 的分层一致。
 */
class GroupInvitationService(
    private val invitationRepository: GroupInvitationRepository,
) {
    fun inviteMembers(
        chatId: String,
        actorId: String,
        requestedUserIds: List<String>,
        maxMembers: Int,
    ): GroupInviteResult = invitationRepository.inviteMembers(chatId, actorId, requestedUserIds, maxMembers)

    fun cancel(inviteId: String, actorId: String): Boolean =
        invitationRepository.cancel(inviteId, actorId)

    fun accept(inviteId: String, userId: String, maxMembers: Int): GroupInviteAcceptOutcome =
        invitationRepository.accept(inviteId, userId, maxMembers)

    fun decline(inviteId: String, userId: String): Boolean =
        invitationRepository.decline(inviteId, userId)

    fun listIncoming(userId: String): List<GroupInvitationDto> =
        invitationRepository.listIncoming(userId)

    fun listForChat(chatId: String): List<GroupInvitationDto> =
        invitationRepository.listForChat(chatId)

    fun get(inviteId: String): GroupInvitationDto? =
        invitationRepository.get(inviteId)

    fun configureToken(
        chatId: String,
        actorId: String,
        rotate: Boolean,
        expiresAt: Long,
        maxUses: Int,
        requireBotDeliverable: Boolean = false,
    ): GroupInviteMutationResult =
        invitationRepository.configureToken(chatId, actorId, rotate, expiresAt, maxUses, requireBotDeliverable)

    fun revokeToken(
        chatId: String,
        actorId: String,
        requireBotDeliverable: Boolean = false,
    ): GroupMemberMutationResult =
        invitationRepository.revokeToken(chatId, actorId, requireBotDeliverable)

    fun consumeToken(token: String, userId: String, maxMembers: Int): JoinGroupInviteResult? =
        invitationRepository.consumeToken(token, userId, maxMembers)

    fun adminRevokeTokens(chatIds: List<String>): List<String> =
        invitationRepository.adminRevokeTokens(chatIds)
}
