package com.maodouchat.server.repository

// Admin 会话导出：会话本体、用户会话设置、消失计时器、免打扰（原 AdminExportChats 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.ChatUserSettings
import com.maodouchat.server.db.Chats
import com.maodouchat.server.model.ChatType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInSubQuery
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

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
