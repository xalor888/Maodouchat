package com.maodouchat.server.repository

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

/** 邀请链接 token：配置/轮换、撤销、批量撤销、核销入群。 */
fun GroupInvitationRepository.configureToken(
    chatId: String,
    actorId: String,
    rotate: Boolean,
    expiresAt: Long,
    maxUses: Int,
    requireBotDeliverable: Boolean = false,
): GroupInviteMutationResult = transaction {
    if (requireBotDeliverable && !BotRepository.isBotDeliverableInTx(actorId, System.currentTimeMillis())) {
        return@transaction GroupInviteMutationResult(GroupMemberMutationResult.FORBIDDEN)
    }
    val access = GroupMutationTransaction.lockForAdmin(chatId, actorId)
    if (access is GroupAdminAccess.Denied) return@transaction GroupInviteMutationResult(access.result)
    val chat = (access as GroupAdminAccess.Allowed).chat
    val existingToken = chat[Chats.groupInviteToken]
    val mustRotate = rotate || existingToken.isNullOrBlank() ||
        chat[Chats.groupInviteExpiresAt] <= System.currentTimeMillis() ||
        chat[Chats.groupInviteUseCount] >= chat[Chats.groupInviteMaxUses]
    if (!mustRotate) {
        val limitsChanged = chat[Chats.groupInviteExpiresAt] != expiresAt ||
            chat[Chats.groupInviteMaxUses] != maxUses
        if (limitsChanged) {
            Chats.update({ Chats.id eq chatId }) {
                it[groupInviteExpiresAt] = expiresAt
                it[groupInviteMaxUses] = maxUses
            }
            GroupMutationTransaction.insertAudit(chatId, actorId, "INVITE_CONFIGURED")
        }
        return@transaction GroupInviteMutationResult(
            GroupMemberMutationResult.UPDATED,
            GroupInviteState(
                token = existingToken,
                expiresAt = expiresAt,
                maxUses = maxUses,
                usedCount = chat[Chats.groupInviteUseCount],
                changed = limitsChanged,
            ),
        )
    }
    val token = generateToken()
    Chats.update({ Chats.id eq chatId }) {
        it[groupInviteToken] = token
        it[groupInviteExpiresAt] = expiresAt
        it[groupInviteMaxUses] = maxUses
        it[groupInviteUseCount] = 0
    }
    GroupMutationTransaction.insertAudit(
        chatId,
        actorId,
        if (rotate) "INVITE_ROTATED" else "INVITE_CONFIGURED",
    )
    GroupInviteMutationResult(
        GroupMemberMutationResult.UPDATED,
        GroupInviteState(token, expiresAt, maxUses, usedCount = 0, changed = true),
    )
}

fun GroupInvitationRepository.revokeToken(
    chatId: String,
    actorId: String,
    requireBotDeliverable: Boolean = false,
): GroupMemberMutationResult = transaction {
    if (requireBotDeliverable && !BotRepository.isBotDeliverableInTx(actorId, System.currentTimeMillis())) {
        return@transaction GroupMemberMutationResult.FORBIDDEN
    }
    val access = GroupMutationTransaction.lockForAdmin(chatId, actorId)
    if (access is GroupAdminAccess.Denied) return@transaction access.result
    val chat = (access as GroupAdminAccess.Allowed).chat
    Chats.update({ Chats.id eq chatId }) {
        it[groupInviteToken] = null
        it[groupInviteExpiresAt] = 0
        it[groupInviteMaxUses] = 0
        it[groupInviteUseCount] = 0
        it[memberRevision] = chat[Chats.memberRevision] + 1
    }
    GroupMutationTransaction.insertAudit(chatId, actorId, "INVITE_REVOKED")
    GroupMemberMutationResult.UPDATED
}

/** 平台管理员批量清空群邀请链接 token（不校验群成员身份、不递增 memberRevision）。 */
fun GroupInvitationRepository.adminRevokeTokens(chatIds: List<String>): List<String> = transaction {
    chatIds.mapNotNull { id ->
        val updatedRows = Chats.update({ Chats.id eq id }) {
            it[groupInviteToken] = null
            it[groupInviteExpiresAt] = 0L
            it[groupInviteMaxUses] = 0
            it[groupInviteUseCount] = 0
        }
        if (updatedRows > 0) id else null
    }
}

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
