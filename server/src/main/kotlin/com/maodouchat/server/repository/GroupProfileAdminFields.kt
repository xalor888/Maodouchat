package com.maodouchat.server.repository

import com.maodouchat.server.db.Chats
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** 群资料管理字段：名称与公告（走 updateAdminField 公共路径）。 */

fun GroupProfileRepository.updateName(
    chatId: String,
    actorId: String,
    name: String,
    requireBotDeliverable: Boolean = false,
): GroupMemberMutationResult = updateAdminField(
    chatId = chatId,
    actorId = actorId,
    requireBotDeliverable = requireBotDeliverable,
    auditAction = "GROUP_RENAMED",
) {
    it[Chats.groupName] = name
}

fun GroupProfileRepository.updateAnnouncement(
    chatId: String,
    actorId: String,
    announcement: String?,
    requireBotDeliverable: Boolean = false,
): GroupMemberMutationResult = updateAdminField(
    chatId = chatId,
    actorId = actorId,
    requireBotDeliverable = requireBotDeliverable,
    auditAction = "ANNOUNCEMENT_UPDATED",
) {
    it[Chats.groupAnnouncement] = announcement
}

internal fun GroupProfileRepository.updateAdminField(
    chatId: String,
    actorId: String,
    requireBotDeliverable: Boolean,
    auditAction: String,
    mutate: (UpdateBuilder<Int>) -> Unit,
): GroupMemberMutationResult = transaction {
    if (requireBotDeliverable && !BotRepository.isBotDeliverableInTx(actorId, System.currentTimeMillis())) {
        return@transaction GroupMemberMutationResult.FORBIDDEN
    }
    val access = GroupMutationTransaction.lockForAdmin(chatId, actorId)
    if (access is Denied) return@transaction access.result
    val chat = (access as Allowed).chat
    Chats.update({ Chats.id eq chatId }) { statement ->
        mutate(statement)
        statement[Chats.memberRevision] = chat[Chats.memberRevision] + 1
    }
    GroupMutationTransaction.insertAudit(chatId, actorId, auditAction)
    GroupMemberMutationResult.UPDATED
}
