package com.maodouchat.server.repository

// 群邀请响应簇：接受、拒绝（原 GroupInvitationLifecycle 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupInvitations
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.ChatType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun GroupInvitationRepository.accept(inviteId: String, userId: String, maxMembers: Int): GroupInviteAcceptOutcome = transaction {
    val invite = GroupInvitations.selectAll().where { GroupInvitations.id eq inviteId }
        .forUpdate().firstOrNull()
        ?: return@transaction GroupInviteAcceptOutcome(GroupInviteAcceptResult.NOT_FOUND)
    if (invite[GroupInvitations.userId] != userId) {
        return@transaction GroupInviteAcceptOutcome(GroupInviteAcceptResult.NOT_INVITEE)
    }
    if (invite[GroupInvitations.status] != STATUS_PENDING) {
        return@transaction GroupInviteAcceptOutcome(GroupInviteAcceptResult.NOT_PENDING)
    }
    val chatId = invite[GroupInvitations.chatId]
    // All member-entry paths lock user before chat to avoid cross-path deadlocks.
    val user = Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull()
    if (user == null || user[Users.deletedAt] != null) {
        return@transaction GroupInviteAcceptOutcome(GroupInviteAcceptResult.USER_DEACTIVATED)
    }
    val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
        ?: return@transaction GroupInviteAcceptOutcome(GroupInviteAcceptResult.CHAT_NOT_FOUND)
    if (!chat[Chats.isGroup]) return@transaction GroupInviteAcceptOutcome(GroupInviteAcceptResult.NOT_GROUP)
    if (chat[Chats.chatType] == ChatType.CHANNEL) {
        return@transaction GroupInviteAcceptOutcome(GroupInviteAcceptResult.CHANNEL_NOT_SUPPORTED)
    }
    val participants = ChatParticipants.selectAll().where { ChatParticipants.chatId eq chatId }.toList()
    if (participants.any { it[ChatParticipants.userId] == userId }) {
        markInvite(inviteId, STATUS_ACCEPTED)
        return@transaction GroupInviteAcceptOutcome(
            GroupInviteAcceptResult.ALREADY_MEMBER,
            chatId = chatId,
            memberRevisionAfter = chat[Chats.memberRevision],
            recipientsAfter = participants.map { it[ChatParticipants.userId] },
        )
    }
    if (participants.size >= maxMembers.coerceAtLeast(0)) {
        return@transaction GroupInviteAcceptOutcome(GroupInviteAcceptResult.MEMBER_LIMIT_EXCEEDED)
    }
    val existingIds = participants.mapTo(linkedSetOf()) { it[ChatParticipants.userId] }
    if (hasBlockedCandidate(existingIds, listOf(userId))) {
        return@transaction GroupInviteAcceptOutcome(GroupInviteAcceptResult.BLOCKED)
    }
    val now = System.currentTimeMillis()
    ChatParticipants.insert {
        it[ChatParticipants.chatId] = chatId
        it[ChatParticipants.userId] = userId
        it[role] = "MEMBER"
        it[joinedAt] = now
    }
    Chats.update({ Chats.id eq chatId }) {
        it[memberRevision] = chat[Chats.memberRevision] + 1
    }
    markInvite(inviteId, STATUS_ACCEPTED, now)
    GroupMutationTransaction.insertAudit(chatId, userId, "MEMBER_JOINED", userId)
    GroupInviteAcceptOutcome(
        GroupInviteAcceptResult.ACCEPTED,
        chatId = chatId,
        memberRevisionAfter = chat[Chats.memberRevision] + 1,
        recipientsAfter = existingIds.toList() + userId,
    )
}


fun GroupInvitationRepository.decline(inviteId: String, userId: String): Boolean = transaction {
    GroupInvitations.update({
        (GroupInvitations.id eq inviteId) and
            (GroupInvitations.userId eq userId) and
            (GroupInvitations.status eq STATUS_PENDING)
    }) {
        it[status] = STATUS_DECLINED
        it[updatedAt] = System.currentTimeMillis()
    } > 0
}
