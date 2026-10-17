package com.maodouchat.server.repository

// 群邀请 token 撤销簇：单群撤销与平台批量撤销（原 GroupInvitationTokens 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.Chats
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun GroupInvitationRepository.revokeToken(
    chatId: String,
    actorId: String,
    requireBotDeliverable: Boolean = false,
): GroupMemberMutationResult = transaction {
    if (requireBotDeliverable && !BotRepository.isBotDeliverableInTx(actorId, System.currentTimeMillis())) {
        return@transaction GroupMemberMutationResult.FORBIDDEN
    }
    val access = GroupMutationTransaction.lockForAdmin(chatId, actorId)
    if (access is Denied) return@transaction access.result
    val chat = (access as Allowed).chat
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
