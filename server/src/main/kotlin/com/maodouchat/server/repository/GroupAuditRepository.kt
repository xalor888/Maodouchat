package com.maodouchat.server.repository

import com.maodouchat.server.db.GroupAuditLogs
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.GroupAuditLogResponse
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** Retention and filtered read boundary for group audit events. */
class GroupAuditRepository {
    fun purgeOlderThan(retentionDays: Int = 365): Int {
        val cutoff = System.currentTimeMillis() - retentionDays.coerceAtLeast(0) * MILLIS_PER_DAY
        return transaction {
            GroupAuditLogs.deleteWhere { GroupAuditLogs.createdAt less cutoff }
        }
    }

    fun list(
        chatId: String,
        limit: Int = 50,
        offset: Int = 0,
        viewerId: String? = null,
    ): List<GroupAuditLogResponse> = transaction {
        val blocked = ConversationVisibility.blockedUserIdsInTx(viewerId)
        val rows = GroupAuditLogs.selectAll()
            .where {
                val inConversation = GroupAuditLogs.chatId eq chatId
                if (blocked.isEmpty()) {
                    inConversation
                } else {
                    inConversation and (GroupAuditLogs.actorId notInList blocked.toList()) and
                        (GroupAuditLogs.targetUserId.isNull() or
                            (GroupAuditLogs.targetUserId notInList blocked.toList()))
                }
            }
            .orderBy(GroupAuditLogs.createdAt to SortOrder.DESC, GroupAuditLogs.id to SortOrder.DESC)
            .limit(limit.coerceIn(1, 100), offset.coerceAtLeast(0).toLong())
            .toList()
        val userIds = rows.flatMap {
            listOfNotNull(it[GroupAuditLogs.actorId], it[GroupAuditLogs.targetUserId])
        }.distinct()
        val names = if (userIds.isEmpty()) {
            emptyMap()
        } else {
            Users.selectAll().where { Users.id inList userIds }.associate { it[Users.id] to it[Users.name] }
        }
        rows.map {
            GroupAuditLogResponse(
                id = it[GroupAuditLogs.id],
                actorId = it[GroupAuditLogs.actorId],
                actorName = names[it[GroupAuditLogs.actorId]].orEmpty(),
                action = it[GroupAuditLogs.action],
                targetUserId = it[GroupAuditLogs.targetUserId],
                targetUserName = it[GroupAuditLogs.targetUserId]?.let(names::get),
                createdAt = it[GroupAuditLogs.createdAt],
            )
        }
    }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
