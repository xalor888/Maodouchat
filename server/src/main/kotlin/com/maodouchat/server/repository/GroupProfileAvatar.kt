package com.maodouchat.server.repository

import com.maodouchat.server.db.Chats
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** 群头像变更：设置与清除（走 mutateAvatar 公共路径）。 */

fun GroupProfileRepository.updateAvatar(
    chatId: String,
    actorId: String,
    avatarUrl: String,
    requireBotDeliverable: Boolean = false,
): GroupAvatarMutationResult = mutateAvatar(
    chatId = chatId,
    actorId = actorId,
    requireBotDeliverable = requireBotDeliverable,
    auditAction = "AVATAR_UPDATED",
    avatarUrl = avatarUrl,
)

fun GroupProfileRepository.clearAvatar(
    chatId: String,
    actorId: String,
    requireBotDeliverable: Boolean = false,
): GroupAvatarMutationResult = mutateAvatar(
    chatId = chatId,
    actorId = actorId,
    requireBotDeliverable = requireBotDeliverable,
    auditAction = "AVATAR_CLEARED",
    avatarUrl = null,
)

internal fun GroupProfileRepository.mutateAvatar(
    chatId: String,
    actorId: String,
    requireBotDeliverable: Boolean,
    auditAction: String,
    avatarUrl: String?,
): GroupAvatarMutationResult = transaction {
    if (requireBotDeliverable && !BotRepository.isBotDeliverableInTx(actorId, System.currentTimeMillis())) {
        return@transaction GroupAvatarMutationResult(GroupMemberMutationResult.FORBIDDEN)
    }
    val access = GroupMutationTransaction.lockForAdmin(chatId, actorId)
    if (access is GroupAdminAccess.Denied) {
        return@transaction GroupAvatarMutationResult(access.result)
    }
    val chat = (access as GroupAdminAccess.Allowed).chat
    val previous = chat[Chats.groupAvatar]
    Chats.update({ Chats.id eq chatId }) {
        it[Chats.groupAvatar] = avatarUrl
        it[Chats.memberRevision] = chat[Chats.memberRevision] + 1
    }
    GroupMutationTransaction.insertAudit(chatId, actorId, auditAction)
    GroupAvatarMutationResult(GroupMemberMutationResult.UPDATED, previous)
}
