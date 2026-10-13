package com.maodouchat.server.repository

// 群邀请发起取消簇：发起、取消（原 GroupInvitationLifecycle 扩展按域拆出，零行为改动）。

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
