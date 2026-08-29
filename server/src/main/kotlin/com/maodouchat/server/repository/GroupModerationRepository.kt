package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** Owns group mute policy and its transactional persistence. */
class GroupModerationRepository {
    fun updateMemberMute(
        chatId: String,
        actorId: String,
        targetUserId: String,
        mutedUntil: Long,
        requireBotDeliverable: Boolean = false,
    ): GroupMemberMutationResult = transaction {
        if (requireBotDeliverable && !BotRepository.isBotDeliverableInTx(actorId, System.currentTimeMillis())) {
            return@transaction GroupMemberMutationResult.FORBIDDEN
        }
        val access = GroupMutationTransaction.lockForAdmin(chatId, actorId)
        if (access is GroupAdminAccess.Denied) return@transaction access.result
        val chat = (access as GroupAdminAccess.Allowed).chat
        val participants = ChatParticipants.selectAll()
            .where { ChatParticipants.chatId eq chatId }
            .toList()
        val actorRole = participants.first { it[ChatParticipants.userId] == actorId }[ChatParticipants.role]
        val target = participants.firstOrNull { it[ChatParticipants.userId] == targetUserId }
            ?: return@transaction GroupMemberMutationResult.TARGET_NOT_PARTICIPANT
        val targetRole = target[ChatParticipants.role]
        when {
            actorId == targetUserId -> return@transaction GroupMemberMutationResult.SELF_NOT_ALLOWED
            targetRole == GroupMutationTransaction.ROLE_OWNER -> {
                return@transaction GroupMemberMutationResult.OWNER_PROTECTED
            }
            actorRole == GroupMutationTransaction.ROLE_ADMIN &&
                targetRole == GroupMutationTransaction.ROLE_ADMIN -> {
                return@transaction GroupMemberMutationResult.PEER_ADMIN_PROTECTED
            }
        }
        val normalizedUntil = mutedUntil.coerceAtLeast(0)
        val updated = ChatParticipants.update({
            (ChatParticipants.chatId eq chatId) and
                (ChatParticipants.userId eq targetUserId)
        }) {
            it[ChatParticipants.mutedUntil] = normalizedUntil
        }
        if (updated != 1) return@transaction GroupMemberMutationResult.TARGET_NOT_PARTICIPANT
        bumpRevision(chatId, chat[Chats.memberRevision])
        GroupMutationTransaction.insertAudit(
            chatId,
            actorId,
            if (normalizedUntil > 0) "MEMBER_MUTED" else "MEMBER_UNMUTED",
            targetUserId,
        )
        GroupMemberMutationResult.UPDATED
    }

    fun updateMembersMute(
        chatId: String,
        actorId: String,
        targetUserIds: List<String>,
        mutedUntil: Long,
        requireBotDeliverable: Boolean = false,
    ): GroupBulkMuteResult = transaction {
        if (requireBotDeliverable && !BotRepository.isBotDeliverableInTx(actorId, System.currentTimeMillis())) {
            return@transaction GroupBulkMuteResult(GroupMemberMutationResult.FORBIDDEN)
        }
        val access = GroupMutationTransaction.lockForAdmin(chatId, actorId)
        if (access is GroupAdminAccess.Denied) return@transaction GroupBulkMuteResult(access.result)
        val chat = (access as GroupAdminAccess.Allowed).chat
        val participants = ChatParticipants.selectAll()
            .where { ChatParticipants.chatId eq chatId }
            .toList()
        val actorRole = participants.first { it[ChatParticipants.userId] == actorId }[ChatParticipants.role]
        val roleByUser = participants.associate {
            it[ChatParticipants.userId] to it[ChatParticipants.role]
        }
        val targets = targetUserIds.distinct().filter { userId ->
            val targetRole = roleByUser[userId]
            userId != actorId &&
                targetRole != null &&
                targetRole != GroupMutationTransaction.ROLE_OWNER &&
                !(actorRole == GroupMutationTransaction.ROLE_ADMIN &&
                    targetRole == GroupMutationTransaction.ROLE_ADMIN)
        }
        if (targets.isEmpty()) {
            return@transaction GroupBulkMuteResult(GroupMemberMutationResult.UPDATED)
        }
        val normalizedUntil = mutedUntil.coerceAtLeast(0)
        val updated = ChatParticipants.update({
            (ChatParticipants.chatId eq chatId) and
                (ChatParticipants.userId inList targets)
        }) {
            it[ChatParticipants.mutedUntil] = normalizedUntil
        }
        if (updated > 0) {
            bumpRevision(chatId, chat[Chats.memberRevision])
            val action = if (normalizedUntil > 0) "MEMBER_MUTED" else "MEMBER_UNMUTED"
            targets.forEach { targetUserId ->
                GroupMutationTransaction.insertAudit(chatId, actorId, action, targetUserId)
            }
        }
        GroupBulkMuteResult(GroupMemberMutationResult.UPDATED, updated)
    }

    private fun bumpRevision(chatId: String, previousRevision: Long) {
        Chats.update({ Chats.id eq chatId }) {
            it[Chats.memberRevision] = previousRevision + 1
        }
    }
}
