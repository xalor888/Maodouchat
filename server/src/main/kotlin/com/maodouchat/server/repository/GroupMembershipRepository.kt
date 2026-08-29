package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.ChatUserSettings
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.GroupAuditLogs
import com.maodouchat.server.db.Users
import java.util.UUID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction

/** SQL boundary for membership, role, ownership, revision and member-scoped cleanup. */
class GroupMembershipRepository {
    fun addMembers(
        chatId: String,
        actorId: String,
        requestedUserIds: List<String>,
        maxMembers: Int,
        requireBotDeliverable: Boolean = false,
    ): AddGroupMembersResult = transaction {
        if (requireBotDeliverable && !BotRepository.isBotDeliverableInTx(actorId, System.currentTimeMillis())) {
            return@transaction AddGroupMembersResult(GroupMemberMutationResult.FORBIDDEN)
        }
        val requestedIds = requestedUserIds.distinct()
        val requestedUsers = lockUsersInTx(requestedIds)
        val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
            ?: return@transaction AddGroupMembersResult(GroupMemberMutationResult.CHAT_NOT_FOUND)
        if (!chat[Chats.isGroup]) return@transaction AddGroupMembersResult(GroupMemberMutationResult.NOT_GROUP)
        val participants = ChatParticipants.selectAll().where { ChatParticipants.chatId eq chatId }.toList()
        val actor = participants.firstOrNull { it[ChatParticipants.userId] == actorId }
            ?: return@transaction AddGroupMembersResult(GroupMemberMutationResult.ACTOR_NOT_PARTICIPANT)
        if (actor[ChatParticipants.role] !in ADMIN_ROLES) {
            return@transaction AddGroupMembersResult(GroupMemberMutationResult.FORBIDDEN)
        }
        val existingIds = participants.mapTo(linkedSetOf()) { it[ChatParticipants.userId] }
        val addedIds = requestedIds.filterNot(existingIds::contains)
        val boundedLimit = maxMembers.coerceAtLeast(0)
        if (existingIds.size > boundedLimit || addedIds.size > boundedLimit - existingIds.size) {
            return@transaction AddGroupMembersResult(GroupMemberMutationResult.MEMBER_LIMIT_EXCEEDED)
        }
        val addedSet = addedIds.toSet()
        val activeIds = requestedUsers
            .filter { it[Users.id] in addedSet && it[Users.deletedAt] == null }
            .mapTo(hashSetOf()) { it[Users.id] }
        val missing = addedIds.firstOrNull { it !in activeIds }
        if (missing != null) {
            return@transaction AddGroupMembersResult(
                GroupMemberMutationResult.USER_NOT_FOUND,
                missingUserId = missing,
            )
        }
        val blockedId = if (existingIds.isEmpty() || addedIds.isEmpty()) null else {
            val involved = existingIds + addedIds
            val pairs = BlockedUsers.select(BlockedUsers.blockerId, BlockedUsers.blockedId)
                .where {
                    (BlockedUsers.blockerId inList involved) and
                        (BlockedUsers.blockedId inList involved)
                }
                .mapTo(hashSetOf()) { it[BlockedUsers.blockerId] to it[BlockedUsers.blockedId] }
            addedIds.firstOrNull { candidate ->
                existingIds.any { memberId ->
                    (memberId to candidate) in pairs || (candidate to memberId) in pairs
                }
            }
        }
        if (blockedId != null) {
            return@transaction AddGroupMembersResult(
                GroupMemberMutationResult.BLOCKED,
                blockedUserId = blockedId,
            )
        }
        val now = System.currentTimeMillis()
        addedIds.forEach { userId ->
            ChatParticipants.insert {
                it[ChatParticipants.chatId] = chatId
                it[ChatParticipants.userId] = userId
                it[role] = "MEMBER"
                it[joinedAt] = now
            }
            insertAuditInTx(chatId, actorId, "MEMBER_ADDED", userId)
        }
        if (addedIds.isNotEmpty()) {
            Chats.update({ Chats.id eq chatId }) {
                it[memberRevision] = chat[Chats.memberRevision] + 1
            }
        }
        AddGroupMembersResult(GroupMemberMutationResult.UPDATED, addedUserIds = addedIds)
    }

    fun addOwnedBot(
        chatId: String,
        actorId: String,
        botId: String,
        maxMembers: Int,
    ): AddOwnedBotResult = transaction {
        val bot = BotApps.selectAll().where { BotApps.id eq botId }.forUpdate().firstOrNull()
        val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
            ?: return@transaction AddOwnedBotResult.CHAT_NOT_FOUND
        if (!chat[Chats.isGroup]) return@transaction AddOwnedBotResult.NOT_GROUP
        val participants = ChatParticipants.selectAll().where { ChatParticipants.chatId eq chatId }.toList()
        val actor = participants.firstOrNull { it[ChatParticipants.userId] == actorId }
            ?: return@transaction AddOwnedBotResult.FORBIDDEN
        if (actor[ChatParticipants.role] !in ADMIN_ROLES) return@transaction AddOwnedBotResult.FORBIDDEN
        if (bot == null) return@transaction AddOwnedBotResult.BOT_NOT_FOUND
        if (bot[BotApps.ownerUserId] != actorId) return@transaction AddOwnedBotResult.BOT_NOT_OWNED
        if (!bot[BotApps.enabled]) return@transaction AddOwnedBotResult.BOT_DISABLED
        if (participants.any { it[ChatParticipants.userId] == botId }) {
            return@transaction AddOwnedBotResult.ALREADY_MEMBER
        }
        if (participants.size >= maxMembers.coerceAtLeast(1)) {
            return@transaction AddOwnedBotResult.MEMBER_LIMIT_EXCEEDED
        }
        ChatParticipants.insert {
            it[ChatParticipants.chatId] = chatId
            it[ChatParticipants.userId] = botId
            it[joinedAt] = System.currentTimeMillis()
            it[role] = ROLE_ADMIN
        }
        Chats.update({ Chats.id eq chatId }) {
            it[memberRevision] = chat[Chats.memberRevision] + 1
        }
        insertAuditInTx(chatId, actorId, "BOT_ADDED", botId)
        AddOwnedBotResult.ADDED
    }

    fun participantIds(chatId: String): List<String> = transaction {
        ChatParticipants.select(ChatParticipants.userId)
            .where { ChatParticipants.chatId eq chatId }
            .map { it[ChatParticipants.userId] }
    }

    fun memberRevision(chatId: String): Long? = transaction {
        Chats.select(Chats.memberRevision)
            .where { Chats.id eq chatId }
            .firstOrNull()
            ?.get(Chats.memberRevision)
    }

    internal fun lockChat(chatId: String) = transaction {
        Chats.select(Chats.id).where { Chats.id eq chatId }.forUpdate().firstOrNull()
        Unit
    }

    fun removeMember(
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

    fun updateRole(
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

    fun transferOwnership(
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

    private fun deleteMemberScopedDataInTx(chatId: String, userId: String) {
        ConversationStateDeletion.deleteParticipantState(chatId, userId)
    }

    private fun insertAuditInTx(chatId: String, actorId: String, action: String, targetUserId: String?) {
        GroupAuditLogs.insert {
            it[id] = "gal_${UUID.randomUUID()}"
            it[GroupAuditLogs.chatId] = chatId
            it[GroupAuditLogs.actorId] = actorId
            it[GroupAuditLogs.action] = action.take(40)
            it[GroupAuditLogs.targetUserId] = targetUserId
            it[createdAt] = System.currentTimeMillis()
        }
    }

    private fun lockUsersInTx(userIds: List<String>): List<ResultRow> {
        val orderedIds = userIds.distinct().sorted()
        if (orderedIds.isEmpty()) return emptyList()
        return Users.selectAll()
            .where { Users.id inList orderedIds }
            .orderBy(Users.id, SortOrder.ASC)
            .forUpdate()
            .toList()
    }

    private companion object {
        const val ROLE_OWNER = "OWNER"
        const val ROLE_ADMIN = "ADMIN"
        val ADMIN_ROLES = setOf(ROLE_OWNER, ROLE_ADMIN)
        val MUTABLE_MEMBER_ROLES = setOf(ROLE_ADMIN, "MEMBER")
    }
}
