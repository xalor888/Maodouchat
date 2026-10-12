package com.maodouchat.server.repository

// 会话管理簇（会话总览/群聊管理）：原 AdminManagementRepository 成员按域拆出的顶层扩展函数（同包调用，零行为改动）。
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.PushTokens
import com.maodouchat.server.db.SignalDevices
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
import com.maodouchat.server.model.ChatAdminResponse
import com.maodouchat.server.model.ChatType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun AdminManagementRepository.signalDevices(userId: String): List<AdminSignalDeviceRow> = transaction {
    SignalDevices.selectAll()
        .where { SignalDevices.userId eq userId }
        .orderBy(SignalDevices.lastSeenAt to SortOrder.DESC)
        .map {
            AdminSignalDeviceRow(
                deviceId = it[SignalDevices.deviceId],
                deviceName = it[SignalDevices.deviceName],
                status = it[SignalDevices.status],
                lastSeenAt = it[SignalDevices.lastSeenAt],
                createdAt = it[SignalDevices.createdAt],
            )
        }
}

fun AdminManagementRepository.pushTokens(userId: String): List<AdminPushTokenRow> = transaction {
    PushTokens.selectAll()
        .where { PushTokens.userId eq userId }
        .orderBy(PushTokens.updatedAt to SortOrder.DESC)
        .map {
            AdminPushTokenRow(
                deviceId = it[PushTokens.deviceId],
                platform = it[PushTokens.platform],
                updatedAt = it[PushTokens.updatedAt],
            )
        }
}

fun AdminManagementRepository.listAdminChats(
    limit: Int,
    offset: Long,
    groupOnly: Boolean,
    search: String?,
): List<ChatAdminResponse> = transaction {
    val query = Chats.selectAll()
    query.andWhere { Chats.chatType neq ChatType.SECRET }
    if (groupOnly) query.andWhere { Chats.isGroup eq true }
    val escapedSearch = search?.let { escapeLikePattern(it) }
    if (escapedSearch != null) query.andWhere { Chats.groupName like "%$escapedSearch%" }

    val rows = query
        .orderBy(Chats.memberRevision to SortOrder.DESC, Chats.id to SortOrder.DESC)
        .limit(limit, offset)
        .toList()
    val chatIds = rows.map { it[Chats.id] }

    val countExpr = ChatParticipants.userId.count()
    val memberCounts: Map<String, Int> = if (chatIds.isEmpty()) {
        emptyMap()
    } else {
        ChatParticipants.slice(ChatParticipants.chatId, countExpr)
            .selectAll().where { ChatParticipants.chatId inList chatIds }
            .groupBy(ChatParticipants.chatId)
            .associate { it[ChatParticipants.chatId] to it[countExpr].toInt() }
    }

    val maxExpr = MessagingV2Messages.serverTimestamp.max()
    val lastMsgMap: Map<String, Long> = if (chatIds.isEmpty()) {
        emptyMap()
    } else {
        MessagingV2Messages.slice(MessagingV2Messages.conversationId, maxExpr)
            .selectAll().where {
                (MessagingV2Messages.conversationId inList chatIds) and
                    (MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE)
            }
            .groupBy(MessagingV2Messages.conversationId)
            .associate { it[MessagingV2Messages.conversationId] to (it[maxExpr] ?: 0L) }
    }

    rows.map { row ->
        val chatId = row[Chats.id]
        ChatAdminResponse(
            id = chatId,
            isGroup = row[Chats.isGroup],
            chatType = row[Chats.chatType],
            groupName = row[Chats.groupName],
            groupAnnouncement = row[Chats.groupAnnouncement],
            memberCount = memberCounts[chatId] ?: 0,
            createdAt = 0L,
            lastActivity = lastMsgMap[chatId] ?: 0L,
        )
    }
}

fun AdminManagementRepository.dissolveGroupChat(chatId: String): Triple<String, List<String>, String?> = transaction {
    val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
        ?: return@transaction Triple("missing", emptyList<String>(), null as String?)
    if (!chat[Chats.isGroup] || chat[Chats.chatType] == ChatType.SECRET) {
        return@transaction Triple("forbidden", emptyList<String>(), null)
    }
    val attachmentIds = ConversationStateDeletion.deleteConversation(chatId)
    Triple("ok", attachmentIds, chat[Chats.groupAvatar])
}
