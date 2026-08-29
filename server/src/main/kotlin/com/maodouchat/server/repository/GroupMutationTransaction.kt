package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupAuditLogs
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

internal sealed interface GroupAdminAccess {
    data class Allowed(val chat: ResultRow) : GroupAdminAccess
    data class Denied(val result: GroupMemberMutationResult) : GroupAdminAccess
}

/** Shared transaction-local authorization and audit primitives for group mutations. */
internal object GroupMutationTransaction {
    fun lockForAdmin(chatId: String, actorId: String): GroupAdminAccess {
        val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
            ?: return GroupAdminAccess.Denied(GroupMemberMutationResult.CHAT_NOT_FOUND)
        if (!chat[Chats.isGroup]) {
            return GroupAdminAccess.Denied(GroupMemberMutationResult.NOT_GROUP)
        }
        val role = ChatParticipants.selectAll()
            .where {
                (ChatParticipants.chatId eq chatId) and
                    (ChatParticipants.userId eq actorId)
            }
            .firstOrNull()
            ?.get(ChatParticipants.role)
            ?: return GroupAdminAccess.Denied(GroupMemberMutationResult.ACTOR_NOT_PARTICIPANT)
        return if (role in ADMIN_ROLES) {
            GroupAdminAccess.Allowed(chat)
        } else {
            GroupAdminAccess.Denied(GroupMemberMutationResult.FORBIDDEN)
        }
    }

    fun insertAudit(
        chatId: String,
        actorId: String,
        action: String,
        targetUserId: String? = null,
    ) {
        GroupAuditLogs.insert {
            it[id] = "gal_${UUID.randomUUID()}"
            it[GroupAuditLogs.chatId] = chatId
            it[GroupAuditLogs.actorId] = actorId
            it[GroupAuditLogs.action] = action.take(40)
            it[GroupAuditLogs.targetUserId] = targetUserId
            it[createdAt] = System.currentTimeMillis()
        }
    }

    const val ROLE_OWNER = "OWNER"
    const val ROLE_ADMIN = "ADMIN"
    val ADMIN_ROLES = setOf(ROLE_OWNER, ROLE_ADMIN)
}
