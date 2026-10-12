package com.maodouchat.server.repository

import com.maodouchat.server.db.GroupAuditLogs
import java.util.UUID
import org.jetbrains.exposed.sql.*

internal const val ROLE_OWNER = "OWNER"
internal const val ROLE_ADMIN = "ADMIN"
internal val ADMIN_ROLES = setOf(ROLE_OWNER, ROLE_ADMIN)
internal val MUTABLE_MEMBER_ROLES = setOf(ROLE_ADMIN, "MEMBER")

/** SQL boundary for membership, role, ownership, revision and member-scoped cleanup. */
class GroupMembershipRepository(
    internal val participantQuery: ConversationParticipantRepository = ConversationParticipantRepository(),
) {
    internal fun deleteMemberScopedDataInTx(chatId: String, userId: String) {
        ConversationStateDeletion.deleteParticipantState(chatId, userId)
    }

    internal fun insertAuditInTx(chatId: String, actorId: String, action: String, targetUserId: String?) {
        GroupAuditLogs.insert {
            it[id] = "gal_${UUID.randomUUID()}"
            it[GroupAuditLogs.chatId] = chatId
            it[GroupAuditLogs.actorId] = actorId
            it[GroupAuditLogs.action] = action.take(40)
            it[GroupAuditLogs.targetUserId] = targetUserId
            it[createdAt] = System.currentTimeMillis()
        }
    }
}
