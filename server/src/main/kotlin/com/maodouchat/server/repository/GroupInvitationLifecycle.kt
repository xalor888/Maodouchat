package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupInvitations
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.ChatType
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** 群邀请生命周期：发起、取消、接受、拒绝。SQL 边界仍在 GroupInvitationRepository。 */
fun GroupInvitationRepository.inviteMembers(
    chatId: String,
    actorId: String,
    requestedUserIds: List<String>,
    maxMembers: Int,
): GroupInviteResult = transaction {
    val requestedIds = requestedUserIds.distinct()
    val lockedRequestedUsers = lockUsers(requestedIds)
    val access = GroupMutationTransaction.lockForAdmin(chatId, actorId)
    if (access is GroupAdminAccess.Denied) return@transaction GroupInviteResult(access.result)
    val chat = (access as GroupAdminAccess.Allowed).chat
    if (chat[Chats.chatType] == ChatType.CHANNEL) {
        return@transaction GroupInviteResult(GroupMemberMutationResult.NOT_GROUP)
    }
    val participants = ChatParticipants.selectAll().where { ChatParticipants.chatId eq chatId }.toList()
    val existingIds = participants.mapTo(linkedSetOf()) { it[ChatParticipants.userId] }
    val newIds = requestedIds.filterNot(existingIds::contains)
    val existingInvites = if (requestedIds.isEmpty()) emptyList() else {
        GroupInvitations.selectAll().where {
            (GroupInvitations.chatId eq chatId) and
                (GroupInvitations.userId inList requestedIds)
        }.toList()
    }
    val inviteByUser = existingInvites.associateBy { it[GroupInvitations.userId] }
    val pendingRequested = newIds.filter { inviteByUser[it]?.get(GroupInvitations.status) == STATUS_PENDING }
    val reopenIds = newIds.filter { userId ->
        inviteByUser[userId]?.get(GroupInvitations.status)?.let { it != STATUS_PENDING } == true
    }
    val toCreate = newIds.filterNot(inviteByUser::containsKey)
    val boundedMaxMembers = maxMembers.coerceAtLeast(0)
    val pendingCount = GroupInvitations.selectAll().where {
        (GroupInvitations.chatId eq chatId) and
            (GroupInvitations.status eq STATUS_PENDING)
    }.count().toInt()
    val remainingSlots = boundedMaxMembers - existingIds.size - pendingCount
    if (existingIds.size > boundedMaxMembers ||
        toCreate.size + reopenIds.size > remainingSlots.coerceAtLeast(0)
    ) {
        return@transaction GroupInviteResult(GroupMemberMutationResult.MEMBER_LIMIT_EXCEEDED)
    }
    val candidateIds = (toCreate + reopenIds).toSet()
    val activeUserIds = lockedRequestedUsers
        .filter { it[Users.id] in candidateIds && it[Users.deletedAt] == null }
        .mapTo(hashSetOf()) { it[Users.id] }
    val missing = (toCreate + reopenIds).firstOrNull { it !in activeUserIds }
    if (missing != null) {
        return@transaction GroupInviteResult(
            result = GroupMemberMutationResult.USER_NOT_FOUND,
            missingUserId = missing,
        )
    }
    if (hasBlockedCandidate(existingIds, toCreate + reopenIds)) {
        return@transaction GroupInviteResult(GroupMemberMutationResult.BLOCKED)
    }
    val now = System.currentTimeMillis()
    toCreate.forEach { userId ->
        GroupInvitations.insert {
            it[id] = "gi_${UUID.randomUUID()}"
            it[GroupInvitations.chatId] = chatId
            it[GroupInvitations.userId] = userId
            it[inviterId] = actorId
            it[status] = STATUS_PENDING
            it[createdAt] = now
            it[updatedAt] = now
        }
        GroupMutationTransaction.insertAudit(chatId, actorId, "MEMBER_INVITED", userId)
    }
    if (reopenIds.isNotEmpty()) {
        GroupInvitations.update({
            (GroupInvitations.chatId eq chatId) and
                (GroupInvitations.userId inList reopenIds)
        }) {
            it[status] = STATUS_PENDING
            it[inviterId] = actorId
            it[updatedAt] = now
        }
        reopenIds.forEach { userId ->
            GroupMutationTransaction.insertAudit(chatId, actorId, "MEMBER_INVITED", userId)
        }
    }
    if (pendingRequested.isNotEmpty()) {
        GroupInvitations.update({
            (GroupInvitations.chatId eq chatId) and
                (GroupInvitations.userId inList pendingRequested) and
                (GroupInvitations.status eq STATUS_PENDING)
        }) {
            it[updatedAt] = now
        }
    }
    GroupInviteResult(
        result = GroupMemberMutationResult.UPDATED,
        invitedUserIds = toCreate + reopenIds + pendingRequested,
        skippedMemberIds = requestedIds.filter(existingIds::contains),
    )
}

fun GroupInvitationRepository.cancel(inviteId: String, actorId: String): Boolean = transaction {
    val invite = GroupInvitations.selectAll().where { GroupInvitations.id eq inviteId }
        .forUpdate().firstOrNull() ?: return@transaction false
    val actor = ChatParticipants.selectAll().where {
        (ChatParticipants.chatId eq invite[GroupInvitations.chatId]) and
            (ChatParticipants.userId eq actorId)
    }.firstOrNull() ?: return@transaction false
    val isInviter = invite[GroupInvitations.inviterId] == actorId
    if (!isInviter && actor[ChatParticipants.role] !in GroupMutationTransaction.ADMIN_ROLES) {
        return@transaction false
    }
    if (invite[GroupInvitations.status] != STATUS_PENDING) return@transaction true
    GroupInvitations.update({ GroupInvitations.id eq inviteId }) {
        it[status] = STATUS_CANCELLED
        it[updatedAt] = System.currentTimeMillis()
    }
    true
}

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
