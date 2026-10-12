package com.maodouchat.server.repository

// 审计簇（审计写入/元数据搜索）：原 AdminManagementRepository 成员按域拆出的顶层扩展函数（同包调用，零行为改动）。
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.ModerationAuditLog
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
import com.maodouchat.server.model.ChatType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInSubQuery
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun AdminManagementRepository.recordAuditBatch(
    actorId: String,
    action: String,
    entries: List<Triple<String, String, Long>>,
) {
    if (entries.isEmpty()) return
    transaction {
        ModerationAuditLog.batchInsert(entries) { (userId, detail, createdAt) ->
            this[ModerationAuditLog.userId] = userId
            this[ModerationAuditLog.action] = action
            this[ModerationAuditLog.detail] = detail
            this[ModerationAuditLog.actorId] = actorId
            this[ModerationAuditLog.createdAt] = createdAt
        }
    }
}

fun AdminManagementRepository.recordAudit(actorId: String?, userId: String?, action: String, detail: String?) {
    transaction {
        ModerationAuditLog.insert {
            it[ModerationAuditLog.actorId] = actorId
            it[ModerationAuditLog.userId] = userId
            it[ModerationAuditLog.action] = action
            it[ModerationAuditLog.detail] = detail
            it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
        }
    }
}

/** Metadata-only search. Human payloads remain opaque to the server. */
fun AdminManagementRepository.searchMessageMetadata(filter: AdminMessageSearchFilter): List<AdminMessageMetadataRow> = transaction {
    var query = MessagingV2Messages.selectAll()
    if (filter.chatId.isNotBlank()) {
        query = query.andWhere { MessagingV2Messages.conversationId eq filter.chatId }
    }
    if (filter.userId.isNotBlank()) {
        query = query.andWhere { MessagingV2Messages.senderUserId eq filter.userId }
    }
    query = query.andWhere {
        (MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE) and
            (MessagingV2Messages.conversationId notInSubQuery (
                Chats.select(Chats.id).where { Chats.chatType eq ChatType.SECRET }
            ))
    }
    if (filter.q.isNotBlank()) {
        val like = "%" + escapeLikePattern(filter.q.take(80)) + "%"
        query = query.andWhere {
            (MessagingV2Messages.id like like) or
                (MessagingV2Messages.conversationId like like) or
                (MessagingV2Messages.senderUserId like like) or
                (MessagingV2Messages.kind like like)
        }
    }
    query.orderBy(
        MessagingV2Messages.serverTimestamp to SortOrder.DESC,
        MessagingV2Messages.id to SortOrder.DESC,
    )
        .limit(filter.limit, filter.offset.toLong())
        .map {
            AdminMessageMetadataRow(
                id = it[MessagingV2Messages.id],
                chatId = it[MessagingV2Messages.conversationId],
                senderId = it[MessagingV2Messages.senderUserId],
                kind = it[MessagingV2Messages.kind],
                timestamp = it[MessagingV2Messages.serverTimestamp],
            )
        }
}
