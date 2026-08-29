package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.ChatType
import com.maodouchat.server.model.GroupMemberResponse
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** Read-only participant, role and membership snapshot boundary. */
class ConversationParticipantRepository {
    fun participantIds(chatId: String): List<String> = transaction {
        ChatParticipants.select(ChatParticipants.userId)
            .where { ChatParticipants.chatId eq chatId }
            .map { it[ChatParticipants.userId] }
    }

    fun groupRevisionAndParticipantIds(chatIds: List<String>): Map<String, Pair<Long, List<String>>> = transaction {
        if (chatIds.isEmpty()) return@transaction emptyMap()
        val revisions = Chats.select(Chats.id, Chats.memberRevision)
            .where { (Chats.id inList chatIds.distinct()) and (Chats.isGroup eq true) }
            .associate { it[Chats.id] to it[Chats.memberRevision] }
        if (revisions.isEmpty()) return@transaction emptyMap()
        val participants = ChatParticipants.selectAll()
            .where { ChatParticipants.chatId inList revisions.keys }
            .groupBy { it[ChatParticipants.chatId] }
            .mapValues { (_, rows) -> rows.map { it[ChatParticipants.userId] } }
        revisions.mapValues { (chatId, revision) -> revision to participants[chatId].orEmpty() }
    }

    fun isParticipant(chatId: String, userId: String): Boolean = transaction {
        ChatParticipants.select(ChatParticipants.userId)
            .where {
                (ChatParticipants.chatId eq chatId) and
                    (ChatParticipants.userId eq userId)
            }
            .limit(1)
            .any()
    }

    fun chatType(chatId: String): String? = transaction {
        Chats.select(Chats.chatType)
            .where { Chats.id eq chatId }
            .firstOrNull()
            ?.get(Chats.chatType)
    }

    fun isChannelOwner(chatId: String, userId: String): Boolean = transaction {
        val type = Chats.select(Chats.chatType)
            .where { Chats.id eq chatId }
            .firstOrNull()
            ?.get(Chats.chatType)
        type == ChatType.CHANNEL && memberRoleInTx(chatId, userId) == ROLE_OWNER
    }

    fun groupMembershipSnapshotForDeletion(userId: String): List<Pair<String, List<String>>> = transaction {
        val memberChatIds = ChatParticipants.select(ChatParticipants.chatId)
            .where { ChatParticipants.userId eq userId }
            .map { it[ChatParticipants.chatId] }
            .distinct()
        if (memberChatIds.isEmpty()) return@transaction emptyList()
        val groupIds = Chats.select(Chats.id)
            .where { (Chats.id inList memberChatIds) and (Chats.isGroup eq true) }
            .mapTo(linkedSetOf()) { it[Chats.id] }
        if (groupIds.isEmpty()) return@transaction emptyList()
        ChatParticipants.selectAll()
            .where { ChatParticipants.chatId inList groupIds }
            .groupBy { it[ChatParticipants.chatId] }
            .map { (chatId, rows) -> chatId to rows.map { it[ChatParticipants.userId] } }
    }

    fun memberRole(chatId: String, userId: String): String? = transaction {
        memberRoleInTx(chatId, userId)
    }

    fun isOwnerOrAdmin(chatId: String, userId: String): Boolean = transaction {
        memberRoleInTx(chatId, userId) in ADMIN_ROLES
    }

    fun groupMembers(chatId: String, viewerId: String? = null): List<GroupMemberResponse> = transaction {
        val blocked = ConversationVisibility.blockedUserIdsInTx(viewerId)
        (ChatParticipants innerJoin Users)
            .selectAll()
            .where { ChatParticipants.chatId eq chatId }
            .filter { it[Users.deletedAt] == null }
            .filterNot { it[Users.id] in blocked }
            .map {
                GroupMemberResponse(
                    userId = it[Users.id],
                    name = it[Users.name],
                    avatar = it[Users.avatar],
                    role = it[ChatParticipants.role],
                    title = it[ChatParticipants.title],
                    groupNickname = it[ChatParticipants.groupNickname],
                    joinedAt = it[ChatParticipants.joinedAt],
                    isOnline = it[Users.showOnline] && it[Users.isOnline],
                    mutedUntil = it[ChatParticipants.mutedUntil],
                )
            }
            .sortedWith(compareBy({ roleOrder(it.role) }, GroupMemberResponse::joinedAt))
    }

    fun mutedUntil(chatId: String, userId: String): Long = transaction {
        ChatParticipants.select(ChatParticipants.mutedUntil)
            .where {
                (ChatParticipants.chatId eq chatId) and
                    (ChatParticipants.userId eq userId)
            }
            .firstOrNull()
            ?.get(ChatParticipants.mutedUntil)
            ?: 0L
    }

    fun isMuted(chatId: String, userId: String, now: Long = System.currentTimeMillis()): Boolean = transaction {
        val member = ChatParticipants.select(ChatParticipants.role, ChatParticipants.mutedUntil)
            .where {
                (ChatParticipants.chatId eq chatId) and
                    (ChatParticipants.userId eq userId)
            }
            .firstOrNull()
            ?: return@transaction false
        member[ChatParticipants.role] != ROLE_OWNER && member[ChatParticipants.mutedUntil] > now
    }

    private fun memberRoleInTx(chatId: String, userId: String): String? =
        ChatParticipants.select(ChatParticipants.role)
            .where {
                (ChatParticipants.chatId eq chatId) and
                    (ChatParticipants.userId eq userId)
            }
            .firstOrNull()
            ?.get(ChatParticipants.role)

    private fun roleOrder(role: String): Int = when (role) {
        ROLE_OWNER -> 0
        ROLE_ADMIN -> 1
        else -> 2
    }

    private companion object {
        const val ROLE_OWNER = "OWNER"
        const val ROLE_ADMIN = "ADMIN"
        val ADMIN_ROLES = setOf(ROLE_OWNER, ROLE_ADMIN)
    }
}
