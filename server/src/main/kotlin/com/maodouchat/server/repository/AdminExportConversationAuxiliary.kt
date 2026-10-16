package com.maodouchat.server.repository

// Admin 会话导出：置顶消息、群邀请、消息传输统计（原 AdminExportChats 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.PinnedMessages
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
import com.maodouchat.server.model.ChatType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInSubQuery
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** 按 kind 聚合的持久化传输统计（只统计 MESSAGE 类，绝不导出消息体）。 */
data class MessageStats(val byKind: List<List<Any?>>, val total: Long)

/** 置顶消息元数据 only — no message bodies / E2EE plaintext。 */
fun AdminExportRepository.pinnedMessages(limit: Int): List<List<Any?>> = transaction {
    PinnedMessages.selectAll()
        .andWhere {
            PinnedMessages.chatId notInSubQuery (
                Chats.select(Chats.id).where { Chats.chatType eq ChatType.SECRET }
            )
        }
        .orderBy(PinnedMessages.pinnedAt to SortOrder.DESC)
        .limit(limit)
        .map { row ->
            listOf(
                row[PinnedMessages.chatId],
                row[PinnedMessages.messageId],
                row[PinnedMessages.pinnedBy],
                row[PinnedMessages.pinnedAt].toString(),
            )
        }
}

/** 邀请元数据 only — no message bodies；token 只给前 12 位。 */
fun AdminExportRepository.groupInvites(limit: Int): List<List<Any?>> = transaction {
    Chats.selectAll()
        .where { Chats.groupInviteToken.isNotNull() }
        .limit(limit)
        .map { row ->
            listOf(
                row[Chats.id],
                (row[Chats.groupInviteToken] ?: "").take(12),
                row[Chats.groupInviteExpiresAt].toString(),
                row[Chats.groupInviteMaxUses].toString(),
                row[Chats.groupInviteUseCount].toString(),
            )
        }
}

fun AdminExportRepository.messageStats(): MessageStats {
    val byKind = transaction {
        MessagingV2Messages
            .slice(MessagingV2Messages.kind, MessagingV2Messages.kind.count())
            .selectAll()
            .where {
                MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE
            }
            .groupBy(MessagingV2Messages.kind)
            .map { row ->
                listOf(
                    row[MessagingV2Messages.kind],
                    row[MessagingV2Messages.kind.count()],
                )
            }
    }
    val total = transaction {
        MessagingV2Messages.selectAll().where {
            MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE
        }.count()
    }
    return MessageStats(byKind, total)
}
