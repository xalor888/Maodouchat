package com.maodouchat.server.repository

// 群成员管理簇：移出、改角色、转让群主（原 GroupMembershipChanges 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.ChatUserSettings
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

fun GroupMembershipRepository.removeMember(
    chatId: String,
    actorId: String,
    targetUserId: String,
    requireBotDeliverable: Boolean = false,
): com.maodouchat.server.repository.GroupMemberMutationResult = transaction {
    if (requireBotDeliverable && !BotRepository.isBotDeliverableInTx(actorId, System.currentTimeMillis())) {
        return@transaction com.maodouchat.server.repository.GroupMemberMutationResult.FORBIDDEN
    }
    val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
        ?: return@transaction com.maodouchat.server.repository.GroupMemberMutationResult.CHAT_NOT_FOUND
    if (!chat[Chats.isGroup]) return@transaction com.maodouchat.server.repository.GroupMemberMutationResult.NOT_GROUP
    val participants = ChatParticipants.selectAll().where { ChatParticipants.chatId eq chatId }.toList()
    val actor = participants.firstOrNull { it[ChatParticipants.userId] == actorId }
        ?: return@transaction com.maodouchat.server.repository.GroupMemberMutationResult.ACTOR_NOT_PARTICIPANT
    val target = participants.firstOrNull { it[ChatParticipants.userId] == targetUserId }
        ?: return@transaction com.maodouchat.server.repository.GroupMemberMutationResult.TARGET_NOT_PARTICIPANT
    val actorRole = actor[ChatParticipants.role]
    val targetRole = target[ChatParticipants.role]
    if (actorRole !in ADMIN_ROLES) return@transaction com.maodouchat.server.repository.GroupMemberMutationResult.FORBIDDEN
    if (actorId == targetUserId) return@transaction com.maodouchat.server.repository.GroupMemberMutationResult.SELF_NOT_ALLOWED
    if (targetRole == ROLE_OWNER) return@transaction com.maodouchat.server.repository.GroupMemberMutationResult.OWNER_PROTECTED
    if (actorRole == ROLE_ADMIN && targetRole == ROLE_ADMIN) {
        return@transaction com.maodouchat.server.repository.GroupMemberMutationResult.PEER_ADMIN_PROTECTED
    }

    ChatUserSettings.deleteWhere {
        (ChatUserSettings.chatId eq chatId) and (ChatUserSettings.userId eq targetUserId)
    }
    deleteMemberScopedDataInTx(chatId, targetUserId)
    val deleted = ChatParticipants.deleteWhere {
        (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq targetUserId)
    }
    if (deleted != 1) return@transaction com.maodouchat.server.repository.GroupMemberMutationResult.TARGET_NOT_PARTICIPANT
    Chats.update({ Chats.id eq chatId }) {
        it[memberRevision] = chat[Chats.memberRevision] + 1
    }
    insertAuditInTx(chatId, actorId, "MEMBER_REMOVED", targetUserId)
    com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED
}


fun GroupMembershipRepository.updateRole(
    chatId: String,
    actorId: String,
    targetUserId: String,
    role: String,
    requireBotDeliverable: Boolean = false,
): com.maodouchat.server.repository.GroupMemberMutationResult = transaction {
    if (role !in MUTABLE_MEMBER_ROLES) return@transaction com.maodouchat.server.repository.GroupMemberMutationResult.FORBIDDEN
    if (requireBotDeliverable && !BotRepository.isBotDeliverableInTx(actorId, System.currentTimeMillis())) {
        return@transaction com.maodouchat.server.repository.GroupMemberMutationResult.FORBIDDEN
    }
    val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
        ?: return@transaction com.maodouchat.server.repository.GroupMemberMutationResult.CHAT_NOT_FOUND
    if (!chat[Chats.isGroup]) return@transaction com.maodouchat.server.repository.GroupMemberMutationResult.NOT_GROUP
    val participants = ChatParticipants.selectAll().where { ChatParticipants.chatId eq chatId }.toList()
    val actor = participants.firstOrNull { it[ChatParticipants.userId] == actorId }
        ?: return@transaction com.maodouchat.server.repository.GroupMemberMutationResult.ACTOR_NOT_PARTICIPANT
    if (actor[ChatParticipants.role] != ROLE_OWNER) return@transaction com.maodouchat.server.repository.GroupMemberMutationResult.FORBIDDEN
    if (actorId == targetUserId) return@transaction com.maodouchat.server.repository.GroupMemberMutationResult.SELF_NOT_ALLOWED
    if (participants.none { it[ChatParticipants.userId] == targetUserId }) {
        return@transaction com.maodouchat.server.repository.GroupMemberMutationResult.TARGET_NOT_PARTICIPANT
    }
    val updated = ChatParticipants.update({
        (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq targetUserId)
    }) { it[ChatParticipants.role] = role }
    if (updated != 1) return@transaction com.maodouchat.server.repository.GroupMemberMutationResult.TARGET_NOT_PARTICIPANT
    Chats.update({ Chats.id eq chatId }) {
        it[memberRevision] = chat[Chats.memberRevision] + 1
    }
    insertAuditInTx(
        chatId,
        actorId,
        if (role == ROLE_ADMIN) "MEMBER_PROMOTED" else "MEMBER_DEMOTED",
        targetUserId,
    )
    com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED
}


fun GroupMembershipRepository.transferOwnership(
    chatId: String,
    ownerId: String,
    targetUserId: String,
): com.maodouchat.server.repository.TransferOwnershipResult = transaction {
    if (ownerId == targetUserId) return@transaction com.maodouchat.server.repository.TransferOwnershipResult.SAME_USER
    val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
        ?: return@transaction com.maodouchat.server.repository.TransferOwnershipResult.CHAT_NOT_FOUND
    if (!chat[Chats.isGroup]) return@transaction com.maodouchat.server.repository.TransferOwnershipResult.NOT_GROUP
    val participants = ChatParticipants.selectAll().where { ChatParticipants.chatId eq chatId }.toList()
    val owner = participants.firstOrNull { it[ChatParticipants.userId] == ownerId }
        ?: return@transaction com.maodouchat.server.repository.TransferOwnershipResult.NOT_OWNER
    if (owner[ChatParticipants.role] != ROLE_OWNER) return@transaction com.maodouchat.server.repository.TransferOwnershipResult.NOT_OWNER
    if (participants.none { it[ChatParticipants.userId] == targetUserId }) {
        return@transaction com.maodouchat.server.repository.TransferOwnershipResult.TARGET_NOT_PARTICIPANT
    }
    val targetUser = Users.selectAll().where { Users.id eq targetUserId }.firstOrNull()
    if (targetUser == null || targetUser[Users.deletedAt] != null) {
        return@transaction com.maodouchat.server.repository.TransferOwnershipResult.TARGET_DEACTIVATED
    }
    ChatParticipants.update({
        (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq ownerId)
    }) { it[role] = ROLE_ADMIN }
    ChatParticipants.update({
        (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq targetUserId)
    }) { it[role] = ROLE_OWNER }
    Chats.update({ Chats.id eq chatId }) {
        it[memberRevision] = chat[Chats.memberRevision] + 1
    }
    insertAuditInTx(chatId, ownerId, "OWNERSHIP_TRANSFERRED", targetUserId)
    com.maodouchat.server.repository.TransferOwnershipResult.TRANSFERRED
}
