package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.ChatUserSettings
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.PinnedMessages
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
import com.maodouchat.server.model.ChatType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInSubQuery
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** 按 kind 聚合的持久化传输统计（只统计 MESSAGE 类，绝不导出消息体）。 */
data class MessageStats(val byKind: List<List<Any?>>, val total: Long)

fun AdminExportRepository.chats(limit: Int): List<List<Any?>> = transaction {
    val chats = Chats.selectAll()
        .where { Chats.chatType neq ChatType.SECRET }
        .orderBy(Chats.memberRevision to SortOrder.DESC)
        .limit(limit)
        .toList()
    // 8.48 修复 H2：GROUP BY 批量成员计数（此前逐会话 count → 最多 1 万次查询）
    val chatIds = chats.map { it[Chats.id] }
    val membersByChat = if (chatIds.isEmpty()) {
        emptyMap()
    } else {
        ChatParticipants
            .slice(ChatParticipants.chatId, ChatParticipants.userId.count())
            .selectAll()
            .where { ChatParticipants.chatId inList chatIds }
            .groupBy(ChatParticipants.chatId)
            .associate { it[ChatParticipants.chatId] to it[ChatParticipants.userId.count()].toLong() }
    }
    chats.map { row ->
        val id = row[Chats.id]
        val type = row[Chats.chatType].ifBlank { if (row[Chats.isGroup]) "GROUP" else "DIRECT" }
        val name = (row[Chats.groupName] ?: "").take(80)
        val members = membersByChat[id] ?: 0L
        listOf(
            id,
            type.lowercase(),
            name,
            members.toString(),
            row[Chats.memberRevision].toString(),
            row[Chats.disappearingMessageSeconds].toString(),
        )
    }
}

/** 每用户会话设置元数据 only — no message bodies / SECRET ids。 */
fun AdminExportRepository.chatSettings(limit: Int): List<List<Any?>> = transaction {
    ChatUserSettings.selectAll()
        .andWhere {
            ChatUserSettings.chatId notInSubQuery (
                Chats.select(Chats.id).where { Chats.chatType eq ChatType.SECRET }
            )
        }
        .orderBy(ChatUserSettings.updatedAt to SortOrder.DESC)
        .limit(limit)
        .map { row ->
            listOf(
                row[ChatUserSettings.userId],
                row[ChatUserSettings.chatId],
                row[ChatUserSettings.pinnedAt].toString(),
                row[ChatUserSettings.notificationsMuted].toString(),
                row[ChatUserSettings.archived].toString(),
                row[ChatUserSettings.markedUnread].toString(),
                row[ChatUserSettings.updatedAt].toString(),
            )
        }
}

/** 会话消失计时器元数据 only — no message bodies。 */
fun AdminExportRepository.disappearingChats(limit: Int): List<List<Any?>> = transaction {
    Chats.selectAll()
        .andWhere { Chats.chatType neq ChatType.SECRET }
        .orderBy(Chats.memberRevision to SortOrder.DESC)
        .limit(limit)
        .mapNotNull { row ->
            val seconds = row[Chats.disappearingMessageSeconds]
            if (seconds <= 0) return@mapNotNull null
            listOf(
                row[Chats.id],
                row[Chats.isGroup].toString(),
                (row[Chats.groupName] ?: "").take(80),
                seconds.toString(),
            )
        }
}

/** 免打扰会话设置元数据 only — no message bodies。 */
fun AdminExportRepository.mutedChats(limit: Int): List<List<Any?>> = transaction {
    ChatUserSettings.selectAll()
        .andWhere {
            ChatUserSettings.chatId notInSubQuery (
                Chats.select(Chats.id).where { Chats.chatType eq ChatType.SECRET }
            )
        }
        .orderBy(ChatUserSettings.updatedAt to SortOrder.DESC)
        .limit(limit * 2)
        .mapNotNull { row ->
            if (!row[ChatUserSettings.notificationsMuted]) return@mapNotNull null
            listOf(
                row[ChatUserSettings.userId],
                row[ChatUserSettings.chatId],
                row[ChatUserSettings.notificationsMuted].toString(),
                row[ChatUserSettings.updatedAt].toString(),
            )
        }
        .take(limit)
}

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
