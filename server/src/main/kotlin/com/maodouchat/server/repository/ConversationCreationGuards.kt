package com.maodouchat.server.repository

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.lockUsersInTx
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** 会话创建校验簇：参与者校验、拉黑检查与失败拒绝。 */

fun ConversationCreationRepository.validateParticipants(userIds: List<String>): ConversationParticipantValidation = transaction {
    validateParticipantsInTx(userIds.distinct())
}

internal fun ConversationCreationRepository.validateParticipantsInTx(userIds: List<String>): ConversationParticipantValidation {
    if (userIds.isEmpty()) {
        return ConversationParticipantValidation(ConversationCreationFailure.PARTICIPANTS_EMPTY)
    }
    val users = lockUsersInTx(userIds)
    val activeIds = users
        .filter { it[Users.deletedAt] == null }
        .mapTo(hashSetOf()) { it[Users.id] }
    val missingUserId = userIds.firstOrNull { it !in activeIds }
    if (missingUserId != null) {
        return ConversationParticipantValidation(
            ConversationCreationFailure.PARTICIPANT_NOT_FOUND,
            missingUserId,
        )
    }
    if (hasBlockedPairInTx(userIds)) {
        return ConversationParticipantValidation(ConversationCreationFailure.PARTICIPANT_BLOCKED)
    }
    return ConversationParticipantValidation()
}

private fun ConversationCreationRepository.hasBlockedPairInTx(userIds: List<String>): Boolean {
    val ids = userIds.distinct()
    if (ids.size < 2) return false
    return BlockedUsers.selectAll().where {
        (BlockedUsers.blockerId inList ids) and (BlockedUsers.blockedId inList ids)
    }.any()
}

internal fun ConversationCreationRepository.reject(failure: ConversationCreationFailure, userId: String? = null): Nothing {
    throw ConversationCreationRejected(failure, userId)
}

internal fun ConversationParticipantValidation.throwIfInvalid() {
    val failure = failure ?: return
    throw ConversationCreationRejected(failure, missingUserId)
}

internal fun ConversationCreationRepository.pairKey(userId1: String, userId2: String): String =
    listOf(userId1, userId2).sorted().joinToString(":")
