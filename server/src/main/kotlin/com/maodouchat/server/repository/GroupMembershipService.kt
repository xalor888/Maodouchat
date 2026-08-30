package com.maodouchat.server.repository

import org.jetbrains.exposed.sql.transactions.transaction

/**
 * B05 子项 1：群成员/角色/转让操作的唯一入口（domain facade）。
 *
 * 所有成员增删、角色调整、群主转让统一经由本服务，集中协调「变更前收件人集合 +
 * 变更后 memberRevision」，供消息层在提交后挂接 sender-key 纪元失效与 WS/Push 广播。
 * 底层 SQL 边界仍在 [GroupMembershipRepository]。
 */
class GroupMembershipService(
    private val membershipRepository: GroupMembershipRepository,
) {
    data class MutationCommit(
        val result: GroupMemberMutationResult,
        val recipientsBefore: List<String>,
        val memberRevisionAfter: Long?,
    )

    data class OwnershipCommit(
        val result: TransferOwnershipResult,
        val recipientsBefore: List<String>,
        val memberRevisionAfter: Long?,
    )

    data class AddMembersCommit(
        val result: AddGroupMembersResult,
        val recipientsBefore: List<String>,
        val memberRevisionAfter: Long?,
    )

    data class AddOwnedBotCommit(
        val result: AddOwnedBotResult,
        val recipientsBefore: List<String>,
        val memberRevisionAfter: Long?,
    )

    fun addMembers(
        chatId: String,
        actorId: String,
        requestedUserIds: List<String>,
        maxMembers: Int,
        requireBotDeliverable: Boolean = false,
    ): AddMembersCommit = transaction {
        val (result, recipientsBefore, revisionAfter) = mutateLocked(chatId) {
            membershipRepository.addMembers(chatId, actorId, requestedUserIds, maxMembers, requireBotDeliverable)
        }
        AddMembersCommit(result, recipientsBefore, revisionAfter)
    }

    fun addOwnedBot(
        chatId: String,
        actorId: String,
        botId: String,
        maxMembers: Int,
    ): AddOwnedBotCommit = transaction {
        val (result, recipientsBefore, revisionAfter) = mutateLocked(chatId) {
            membershipRepository.addOwnedBot(chatId, actorId, botId, maxMembers)
        }
        AddOwnedBotCommit(result, recipientsBefore, revisionAfter)
    }

    fun removeMember(
        chatId: String,
        actorId: String,
        targetUserId: String,
        requireBotDeliverable: Boolean = false,
    ): MutationCommit = transaction {
        val (result, recipientsBefore, revisionAfter) = mutateLocked(chatId) {
            membershipRepository.removeMember(chatId, actorId, targetUserId, requireBotDeliverable)
        }
        MutationCommit(result, recipientsBefore, revisionAfter)
    }

    fun updateRole(
        chatId: String,
        ownerId: String,
        targetUserId: String,
        role: String,
        requireBotDeliverable: Boolean = false,
    ): MutationCommit = transaction {
        val (result, recipientsBefore, revisionAfter) = mutateLocked(chatId) {
            membershipRepository.updateRole(chatId, ownerId, targetUserId, role, requireBotDeliverable)
        }
        MutationCommit(result, recipientsBefore, revisionAfter)
    }

    fun transferOwnership(
        chatId: String,
        ownerId: String,
        targetUserId: String,
    ): OwnershipCommit = transaction {
        val (result, recipientsBefore, revisionAfter) = mutateLocked(chatId) {
            membershipRepository.transferOwnership(chatId, ownerId, targetUserId)
        }
        OwnershipCommit(result, recipientsBefore, revisionAfter)
    }

    private fun <T> mutateLocked(
        chatId: String,
        operation: () -> T,
    ): Triple<T, List<String>, Long?> {
        membershipRepository.lockChat(chatId)
        val recipientsBefore = membershipRepository.participantIds(chatId)
        val result = operation()
        return Triple(result, recipientsBefore, membershipRepository.memberRevision(chatId))
    }
}
