package com.maodouchat.server.repository

// 群邀请 token 核销簇：凭 token 入群（原 GroupInvitationTokens 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.ChatType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun GroupInvitationRepository.consumeToken(token: String, userId: String, maxMembers: Int): JoinGroupInviteResult? {
    val normalized = token.trim()
    if (!INVITE_TOKEN_REGEX.matches(normalized)) return null
    return transaction {
        val joiner = lockUsers(listOf(userId)).firstOrNull()
        if (joiner == null || joiner[Users.deletedAt] != null) return@transaction null
        val chat = Chats.selectAll()
            .where { (Chats.groupInviteToken eq normalized) and (Chats.isGroup eq true) }
            .forUpdate()
            .limit(1)
            .firstOrNull() ?: return@transaction null
        val now = System.currentTimeMillis()
        if (chat[Chats.groupInviteExpiresAt] <= now ||
            chat[Chats.groupInviteUseCount] >= chat[Chats.groupInviteMaxUses]
        ) return@transaction null
        val chatId = chat[Chats.id]
        if (chat[Chats.chatType] == ChatType.CHANNEL) {
            return@transaction JoinGroupInviteResult(chatId, false, channelRejected = true)
        }
        val alreadyMember = ChatParticipants.selectAll().where {
            (ChatParticipants.chatId eq chatId) and
                (ChatParticipants.userId eq userId)
        }.limit(1).any()
        if (!alreadyMember) {
            val memberIds = ChatParticipants.selectAll()
                .where { ChatParticipants.chatId eq chatId }
                .map { it[ChatParticipants.userId] }
            if (hasBlockedCandidate(memberIds.toSet(), listOf(userId))) {
                return@transaction JoinGroupInviteResult(chatId, false, blocked = true)
            }
            if (memberIds.size >= maxMembers.coerceAtLeast(0)) {
                return@transaction JoinGroupInviteResult(chatId, false, limitExceeded = true)
            }
            ChatParticipants.insert {
                it[ChatParticipants.chatId] = chatId
                it[ChatParticipants.userId] = userId
                it[role] = "MEMBER"
                it[joinedAt] = now
            }
            Chats.update({ Chats.id eq chatId }) {
                it[groupInviteUseCount] = chat[Chats.groupInviteUseCount] + 1
                it[memberRevision] = chat[Chats.memberRevision] + 1
            }
            GroupMutationTransaction.insertAudit(chatId, userId, "MEMBER_JOINED", userId)
            return@transaction JoinGroupInviteResult(
                chatId = chatId,
                newlyJoined = true,
                memberRevisionAfter = chat[Chats.memberRevision] + 1,
                recipientsAfter = memberIds + userId,
            )
        }
        JoinGroupInviteResult(chatId, newlyJoined = false)
    }
}
