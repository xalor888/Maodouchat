package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupInvitations
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.ChatType
import com.maodouchat.server.model.GroupInvitationDto
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** 群邀请查询：我的收件箱、按群列出、单条读取。 */
fun GroupInvitationRepository.listIncoming(userId: String): List<GroupInvitationDto> = transaction {
    val rows = GroupInvitations
        .join(Chats, JoinType.INNER, GroupInvitations.chatId, Chats.id)
        .join(Users, JoinType.INNER, GroupInvitations.inviterId, Users.id)
        .select(
            GroupInvitations.id,
            GroupInvitations.chatId,
            GroupInvitations.userId,
            GroupInvitations.inviterId,
            GroupInvitations.status,
            GroupInvitations.createdAt,
            GroupInvitations.updatedAt,
            Chats.groupName,
            Chats.groupAvatar,
            Chats.chatType,
            Users.name,
        )
        .where {
            (GroupInvitations.userId eq userId) and
                (GroupInvitations.status eq STATUS_PENDING)
        }
        .orderBy(GroupInvitations.createdAt, SortOrder.DESC)
        .toList()
    val chatIds = rows.mapTo(linkedSetOf()) { it[GroupInvitations.chatId] }
    val memberCounts = if (chatIds.isEmpty()) emptyMap() else {
        ChatParticipants.select(ChatParticipants.chatId)
            .where { ChatParticipants.chatId inList chatIds }
            .groupingBy { it[ChatParticipants.chatId] }
            .eachCount()
    }
    rows.map { row ->
        val chatId = row[GroupInvitations.chatId]
        GroupInvitationDto(
            id = row[GroupInvitations.id],
            chatId = chatId,
            userId = row[GroupInvitations.userId],
            inviterId = row[GroupInvitations.inviterId],
            inviterName = row[Users.name],
            chatName = row[Chats.groupName].orEmpty(),
            chatAvatar = row[Chats.groupAvatar],
            chatType = row[Chats.chatType],
            memberCount = memberCounts[chatId] ?: 0,
            status = row[GroupInvitations.status],
            createdAt = row[GroupInvitations.createdAt],
            updatedAt = row[GroupInvitations.updatedAt],
        )
    }
}

fun GroupInvitationRepository.listForChat(chatId: String): List<GroupInvitationDto> = transaction {
    GroupInvitations
        .join(Users, JoinType.INNER, GroupInvitations.inviterId, Users.id)
        .select(
            GroupInvitations.id,
            GroupInvitations.chatId,
            GroupInvitations.userId,
            GroupInvitations.inviterId,
            GroupInvitations.status,
            GroupInvitations.createdAt,
            GroupInvitations.updatedAt,
            Users.name,
        )
        .where {
            (GroupInvitations.chatId eq chatId) and
                (GroupInvitations.status eq STATUS_PENDING)
        }
        .orderBy(GroupInvitations.createdAt, SortOrder.DESC)
        .map { row ->
            GroupInvitationDto(
                id = row[GroupInvitations.id],
                chatId = row[GroupInvitations.chatId],
                userId = row[GroupInvitations.userId],
                inviterId = row[GroupInvitations.inviterId],
                inviterName = row[Users.name],
                status = row[GroupInvitations.status],
                createdAt = row[GroupInvitations.createdAt],
                updatedAt = row[GroupInvitations.updatedAt],
            )
        }
}

fun GroupInvitationRepository.get(inviteId: String): GroupInvitationDto? = transaction {
    val row = GroupInvitations.selectAll().where { GroupInvitations.id eq inviteId }.firstOrNull()
        ?: return@transaction null
    val chat = Chats.selectAll().where { Chats.id eq row[GroupInvitations.chatId] }.firstOrNull()
    val inviter = Users.selectAll().where { Users.id eq row[GroupInvitations.inviterId] }.firstOrNull()
    GroupInvitationDto(
        id = row[GroupInvitations.id],
        chatId = row[GroupInvitations.chatId],
        userId = row[GroupInvitations.userId],
        inviterId = row[GroupInvitations.inviterId],
        inviterName = inviter?.get(Users.name).orEmpty(),
        chatName = chat?.get(Chats.groupName).orEmpty(),
        chatAvatar = chat?.get(Chats.groupAvatar),
        chatType = chat?.get(Chats.chatType) ?: ChatType.GROUP,
        status = row[GroupInvitations.status],
        createdAt = row[GroupInvitations.createdAt],
        updatedAt = row[GroupInvitations.updatedAt],
    )
}
