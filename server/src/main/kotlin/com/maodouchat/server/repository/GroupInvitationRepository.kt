package com.maodouchat.server.repository

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.GroupInvitations
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.ChatType
import com.maodouchat.server.model.GroupInvitationDto
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** Durable invitation approval and invite-link state machine. */
class GroupInvitationRepository {
    fun inviteMembers(
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

    fun cancel(inviteId: String, actorId: String): Boolean = transaction {
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

    fun accept(inviteId: String, userId: String, maxMembers: Int): GroupInviteAcceptOutcome = transaction {
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

    fun decline(inviteId: String, userId: String): Boolean = transaction {
        GroupInvitations.update({
            (GroupInvitations.id eq inviteId) and
                (GroupInvitations.userId eq userId) and
                (GroupInvitations.status eq STATUS_PENDING)
        }) {
            it[status] = STATUS_DECLINED
            it[updatedAt] = System.currentTimeMillis()
        } > 0
    }

    fun listIncoming(userId: String): List<GroupInvitationDto> = transaction {
        val rows = GroupInvitations
            .join(Chats, JoinType.INNER, GroupInvitations.chatId, Chats.id)
            .join(Users, JoinType.INNER, GroupInvitations.inviterId, Users.id)
            .select(
                GroupInvitations.id,
                GroupInvitations.chatId,
                GroupInvitations.userId,
                GroupInvitations.inviterId,
                GroupInvitations.status,
                GroupInvitations.createdAt,
                GroupInvitations.updatedAt,
                Chats.groupName,
                Chats.groupAvatar,
                Chats.chatType,
                Users.name,
            )
            .where {
                (GroupInvitations.userId eq userId) and
                    (GroupInvitations.status eq STATUS_PENDING)
            }
            .orderBy(GroupInvitations.createdAt, SortOrder.DESC)
            .toList()
        val chatIds = rows.mapTo(linkedSetOf()) { it[GroupInvitations.chatId] }
        val memberCounts = if (chatIds.isEmpty()) emptyMap() else {
            ChatParticipants.select(ChatParticipants.chatId)
                .where { ChatParticipants.chatId inList chatIds }
                .groupingBy { it[ChatParticipants.chatId] }
                .eachCount()
        }
        rows.map { row ->
            val chatId = row[GroupInvitations.chatId]
            GroupInvitationDto(
                id = row[GroupInvitations.id],
                chatId = chatId,
                userId = row[GroupInvitations.userId],
                inviterId = row[GroupInvitations.inviterId],
                inviterName = row[Users.name],
                chatName = row[Chats.groupName].orEmpty(),
                chatAvatar = row[Chats.groupAvatar],
                chatType = row[Chats.chatType],
                memberCount = memberCounts[chatId] ?: 0,
                status = row[GroupInvitations.status],
                createdAt = row[GroupInvitations.createdAt],
                updatedAt = row[GroupInvitations.updatedAt],
            )
        }
    }

    fun listForChat(chatId: String): List<GroupInvitationDto> = transaction {
        GroupInvitations
            .join(Users, JoinType.INNER, GroupInvitations.inviterId, Users.id)
            .select(
                GroupInvitations.id,
                GroupInvitations.chatId,
                GroupInvitations.userId,
                GroupInvitations.inviterId,
                GroupInvitations.status,
                GroupInvitations.createdAt,
                GroupInvitations.updatedAt,
                Users.name,
            )
            .where {
                (GroupInvitations.chatId eq chatId) and
                    (GroupInvitations.status eq STATUS_PENDING)
            }
            .orderBy(GroupInvitations.createdAt, SortOrder.DESC)
            .map { row ->
                GroupInvitationDto(
                    id = row[GroupInvitations.id],
                    chatId = row[GroupInvitations.chatId],
                    userId = row[GroupInvitations.userId],
                    inviterId = row[GroupInvitations.inviterId],
                    inviterName = row[Users.name],
                    status = row[GroupInvitations.status],
                    createdAt = row[GroupInvitations.createdAt],
                    updatedAt = row[GroupInvitations.updatedAt],
                )
            }
    }

    fun get(inviteId: String): GroupInvitationDto? = transaction {
        val row = GroupInvitations.selectAll().where { GroupInvitations.id eq inviteId }.firstOrNull()
            ?: return@transaction null
        val chat = Chats.selectAll().where { Chats.id eq row[GroupInvitations.chatId] }.firstOrNull()
        val inviter = Users.selectAll().where { Users.id eq row[GroupInvitations.inviterId] }.firstOrNull()
        GroupInvitationDto(
            id = row[GroupInvitations.id],
            chatId = row[GroupInvitations.chatId],
            userId = row[GroupInvitations.userId],
            inviterId = row[GroupInvitations.inviterId],
            inviterName = inviter?.get(Users.name).orEmpty(),
            chatName = chat?.get(Chats.groupName).orEmpty(),
            chatAvatar = chat?.get(Chats.groupAvatar),
            chatType = chat?.get(Chats.chatType) ?: ChatType.GROUP,
            status = row[GroupInvitations.status],
            createdAt = row[GroupInvitations.createdAt],
            updatedAt = row[GroupInvitations.updatedAt],
        )
    }

    fun configureToken(
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

    fun revokeToken(
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

    fun consumeToken(token: String, userId: String, maxMembers: Int): JoinGroupInviteResult? {
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

    private fun hasBlockedCandidate(existingIds: Set<String>, candidates: List<String>): Boolean {
        if (existingIds.isEmpty() || candidates.isEmpty()) return false
        val involved = existingIds + candidates
        val pairs = BlockedUsers.select(BlockedUsers.blockerId, BlockedUsers.blockedId)
            .where {
                (BlockedUsers.blockerId inList involved) and
                    (BlockedUsers.blockedId inList involved)
            }
            .mapTo(hashSetOf()) { it[BlockedUsers.blockerId] to it[BlockedUsers.blockedId] }
        return candidates.any { candidate ->
            existingIds.any { memberId ->
                (memberId to candidate) in pairs || (candidate to memberId) in pairs
            }
        }
    }

    private fun lockUsers(userIds: List<String>): List<ResultRow> {
        val ordered = userIds.distinct().sorted()
        if (ordered.isEmpty()) return emptyList()
        return Users.selectAll()
            .where { Users.id inList ordered }
            .orderBy(Users.id, SortOrder.ASC)
            .forUpdate()
            .toList()
    }

    private fun markInvite(inviteId: String, status: String, now: Long = System.currentTimeMillis()) {
        GroupInvitations.update({ GroupInvitations.id eq inviteId }) {
            it[GroupInvitations.status] = status
            it[updatedAt] = now
        }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SECURE_RANDOM.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_ACCEPTED = "ACCEPTED"
        const val STATUS_DECLINED = "DECLINED"
        const val STATUS_CANCELLED = "CANCELLED"
        val INVITE_TOKEN_REGEX = Regex("^[A-Za-z0-9_-]{32,80}$")
        val SECURE_RANDOM = SecureRandom()
    }
}
