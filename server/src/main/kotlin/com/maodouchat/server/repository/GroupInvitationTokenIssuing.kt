package com.maodouchat.server.repository

// 群邀请 token 签发簇：配置/轮换邀请链接 token（原 GroupInvitationTokens 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.Chats
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

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
