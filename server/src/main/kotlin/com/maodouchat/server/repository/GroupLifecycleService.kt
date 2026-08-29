package com.maodouchat.server.repository

import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Domain facade for group membership mutations.
 *
 * Routes need the participant set that existed before a mutation (for revision
 * broadcasts) and the revision after it. Returning both from one service call
 * keeps that coordination out of HTTP handlers and gives the messaging layer a
 * single place to attach sender-key epoch invalidation later.
 */
class GroupLifecycleService(
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

    fun removeMember(
        chatId: String,
        actorId: String,
        targetUserId: String,
        requireBotDeliverable: Boolean = false,
    ): MutationCommit = transaction {
        mutateLocked(chatId) {
            membershipRepository.removeMember(chatId, actorId, targetUserId, requireBotDeliverable)
        }
    }

    fun updateRole(
        chatId: String,
        ownerId: String,
        targetUserId: String,
        role: String,
        requireBotDeliverable: Boolean = false,
    ): MutationCommit =
        transaction { mutateLocked(chatId) {
            membershipRepository.updateRole(chatId, ownerId, targetUserId, role, requireBotDeliverable)
        } }

    fun transferOwnership(
        chatId: String,
        ownerId: String,
        targetUserId: String,
    ): OwnershipCommit {
        return transaction {
            membershipRepository.lockChat(chatId)
            val recipientsBefore = membershipRepository.participantIds(chatId)
            val result = membershipRepository.transferOwnership(chatId, ownerId, targetUserId)
            OwnershipCommit(
                result = result,
                recipientsBefore = recipientsBefore,
                memberRevisionAfter = membershipRepository.memberRevision(chatId),
            )
        }
    }

    private fun mutateLocked(
        chatId: String,
        operation: () -> GroupMemberMutationResult,
    ): MutationCommit {
        membershipRepository.lockChat(chatId)
        val recipientsBefore = membershipRepository.participantIds(chatId)
        val result = operation()
        return MutationCommit(
            result = result,
            recipientsBefore = recipientsBefore,
            memberRevisionAfter = membershipRepository.memberRevision(chatId),
        )
    }

}
