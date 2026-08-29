package com.maodouchat.server.repository

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.DirectChatPairs
import com.maodouchat.server.db.SecretChatPairs
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.ChatType
import com.maodouchat.server.service.DisappearingMessagePolicy
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

data class CreatedConversation(val id: String)

enum class ConversationCreationFailure {
    PARTICIPANTS_EMPTY,
    CREATOR_NOT_PARTICIPANT,
    PARTICIPANT_NOT_FOUND,
    PARTICIPANT_BLOCKED,
    DIRECT_SELF,
}

class ConversationCreationRejected(
    val failure: ConversationCreationFailure,
    val userId: String? = null,
) : IllegalArgumentException(failure.name.lowercase())

data class ConversationParticipantValidation(
    val failure: ConversationCreationFailure? = null,
    val missingUserId: String? = null,
) {
    val valid: Boolean get() = failure == null
}

/** Transactional write model for group, direct and secret conversation creation. */
class ConversationCreationRepository {
    fun create(
        participantIds: List<String>,
        isGroup: Boolean = false,
        groupName: String? = null,
        creatorId: String? = null,
        chatType: String = if (isGroup) ChatType.GROUP else ChatType.DIRECT,
    ): CreatedConversation = transaction {
        val uniqueParticipantIds = participantIds.distinct()
        if (uniqueParticipantIds.isEmpty()) reject(ConversationCreationFailure.PARTICIPANTS_EMPTY)
        if (creatorId != null && creatorId !in uniqueParticipantIds) {
            reject(ConversationCreationFailure.CREATOR_NOT_PARTICIPANT, creatorId)
        }
        validateParticipantsInTx(uniqueParticipantIds).throwIfInvalid()
        val chatId = "c_${UUID.randomUUID()}"
        Chats.insert {
            it[id] = chatId
            it[Chats.isGroup] = isGroup
            it[Chats.chatType] = chatType
            it[Chats.groupName] = groupName
            it[memberRevision] = if (isGroup) 1 else 0
        }
        val now = System.currentTimeMillis()
        ChatParticipants.batchInsert(uniqueParticipantIds) { userId ->
            this[ChatParticipants.chatId] = chatId
            this[ChatParticipants.userId] = userId
            this[ChatParticipants.joinedAt] = now
            this[ChatParticipants.role] = if (
                isGroup && userId == (creatorId ?: uniqueParticipantIds.first())
            ) ROLE_OWNER else ROLE_MEMBER
        }
        CreatedConversation(chatId)
    }

    fun getOrCreateDirect(userId1: String, userId2: String): CreatedConversation {
        if (userId1 == userId2) reject(ConversationCreationFailure.DIRECT_SELF, userId1)
        val pairKey = pairKey(userId1, userId2)
        return try {
            transaction {
                validateParticipantsInTx(listOf(userId1, userId2)).throwIfInvalid()
                val existingId = lookupDirectIdInTx(pairKey, userId1, userId2)
                if (existingId != null) {
                    ensureDirectMappingInTx(pairKey, existingId, userId1, userId2)
                    return@transaction CreatedConversation(existingId)
                }
                createPairedConversationInTx(
                        pairKey = pairKey,
                        userId1 = userId1,
                        userId2 = userId2,
                        secret = false,
                    )
            }
        } catch (error: Exception) {
            if (!isUniqueViolation(error)) throw error
            findDirect(userId1, userId2) ?: throw error
        }
    }

    fun getOrCreateSecret(userId1: String, userId2: String): CreatedConversation {
        if (userId1 == userId2) reject(ConversationCreationFailure.DIRECT_SELF, userId1)
        val pairKey = pairKey(userId1, userId2)
        return try {
            transaction {
                validateParticipantsInTx(listOf(userId1, userId2)).throwIfInvalid()
                lookupSecretIdInTx(pairKey, userId1, userId2)?.let(::CreatedConversation)
                    ?: createPairedConversationInTx(
                        pairKey = pairKey,
                        userId1 = userId1,
                        userId2 = userId2,
                        secret = true,
                    )
            }
        } catch (error: Exception) {
            if (!isUniqueViolation(error)) throw error
            findSecret(userId1, userId2) ?: throw error
        }
    }

    /** Finds and repairs the unique mapping for an existing direct conversation. */
    fun findDirect(userId1: String, userId2: String): CreatedConversation? {
        if (userId1 == userId2) return null
        val pairKey = pairKey(userId1, userId2)
        val chatId = transaction {
            if (!hasActivePairInTx(userId1, userId2)) return@transaction null
            lookupDirectIdInTx(pairKey, userId1, userId2)
        } ?: return null
        ensureDirectMapping(pairKey, chatId, userId1, userId2)
        return CreatedConversation(chatId)
    }

    fun findSecret(userId1: String, userId2: String): CreatedConversation? {
        if (userId1 == userId2) return null
        val pairKey = pairKey(userId1, userId2)
        return transaction {
            if (!hasActivePairInTx(userId1, userId2)) return@transaction null
            lookupSecretIdInTx(pairKey, userId1, userId2)?.let(::CreatedConversation)
        }
    }

    fun validateParticipants(userIds: List<String>): ConversationParticipantValidation = transaction {
        validateParticipantsInTx(userIds.distinct())
    }

    private fun createPairedConversationInTx(
        pairKey: String,
        userId1: String,
        userId2: String,
        secret: Boolean,
    ): CreatedConversation {
        val chatId = if (secret) "s_${UUID.randomUUID()}" else "c_${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        Chats.insert {
            it[id] = chatId
            it[isGroup] = false
            it[chatType] = if (secret) ChatType.SECRET else ChatType.DIRECT
            it[groupName] = null
            it[memberRevision] = 0
            if (secret) {
                it[disappearingMessageSeconds] = DisappearingMessagePolicy.SECRET_DEFAULT_SECONDS
            }
        }
        listOf(userId1, userId2).forEach { userId ->
            ChatParticipants.insert {
                it[ChatParticipants.chatId] = chatId
                it[ChatParticipants.userId] = userId
                it[joinedAt] = now
                it[role] = ROLE_MEMBER
            }
        }
        if (secret) {
            SecretChatPairs.insert {
                it[SecretChatPairs.pairKey] = pairKey
                it[SecretChatPairs.chatId] = chatId
                it[createdAt] = now
            }
        } else {
            DirectChatPairs.insert {
                it[DirectChatPairs.pairKey] = pairKey
                it[DirectChatPairs.chatId] = chatId
                it[createdAt] = now
            }
        }
        return CreatedConversation(chatId)
    }

    private fun lookupDirectIdInTx(pairKey: String, userId1: String, userId2: String): String? {
        val mapped = DirectChatPairs.selectAll()
            .where { DirectChatPairs.pairKey eq pairKey }
            .firstOrNull()
            ?.get(DirectChatPairs.chatId)
        if (mapped != null) {
            if (isIntactPairInTx(mapped, userId1, userId2, secret = false)) return mapped
            DirectChatPairs.deleteWhere { DirectChatPairs.pairKey eq pairKey }
        }
        return findLegacyDirectIdInTx(userId1, userId2)
    }

    private fun lookupSecretIdInTx(pairKey: String, userId1: String, userId2: String): String? {
        val mapped = SecretChatPairs.selectAll()
            .where { SecretChatPairs.pairKey eq pairKey }
            .firstOrNull()
            ?.get(SecretChatPairs.chatId)
        if (mapped == null) return null
        if (isIntactPairInTx(mapped, userId1, userId2, secret = true)) return mapped
        SecretChatPairs.deleteWhere { SecretChatPairs.pairKey eq pairKey }
        return null
    }

    private fun isIntactPairInTx(
        chatId: String,
        userId1: String,
        userId2: String,
        secret: Boolean,
    ): Boolean {
        val chat = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull() ?: return false
        if (chat[Chats.isGroup]) return false
        if ((chat[Chats.chatType] == ChatType.SECRET) != secret) return false
        val members = ChatParticipants.selectAll()
            .where { ChatParticipants.chatId eq chatId }
            .mapTo(hashSetOf()) { it[ChatParticipants.userId] }
        return members.size == 2 && userId1 in members && userId2 in members
    }

    private fun ensureDirectMapping(pairKey: String, chatId: String, userId1: String, userId2: String) {
        try {
            transaction {
                if (!hasActivePairInTx(userId1, userId2)) return@transaction
                ensureDirectMappingInTx(pairKey, chatId, userId1, userId2)
            }
        } catch (error: Exception) {
            if (!isUniqueViolation(error)) throw error
        }
    }

    private fun ensureDirectMappingInTx(
        pairKey: String,
        chatId: String,
        userId1: String,
        userId2: String,
    ) {
        Chats.select(Chats.id).where { Chats.id eq chatId }.forUpdate().firstOrNull() ?: return
        if (!isIntactPairInTx(chatId, userId1, userId2, secret = false)) return
        if (DirectChatPairs.selectAll().where { DirectChatPairs.pairKey eq pairKey }.any()) return
        DirectChatPairs.insert {
            it[DirectChatPairs.pairKey] = pairKey
            it[DirectChatPairs.chatId] = chatId
            it[createdAt] = System.currentTimeMillis()
        }
    }

    private fun findLegacyDirectIdInTx(userId1: String, userId2: String): String? {
        val firstIds = ChatParticipants.select(ChatParticipants.chatId)
            .where { ChatParticipants.userId eq userId1 }
            .mapTo(hashSetOf()) { it[ChatParticipants.chatId] }
        val commonIds = ChatParticipants.select(ChatParticipants.chatId)
            .where { ChatParticipants.userId eq userId2 }
            .map { it[ChatParticipants.chatId] }
            .filterTo(linkedSetOf()) { it in firstIds }
        if (commonIds.isEmpty()) return null
        val chats = Chats.selectAll().where { Chats.id inList commonIds }
            .associateBy { it[Chats.id] }
        val counts = ChatParticipants.selectAll().where { ChatParticipants.chatId inList commonIds }
            .groupingBy { it[ChatParticipants.chatId] }
            .eachCount()
        return commonIds.asSequence()
            .filter { chatId ->
                val chat = chats[chatId]
                chat != null &&
                    !chat[Chats.isGroup] &&
                    chat[Chats.chatType] != ChatType.SECRET &&
                    counts[chatId] == 2
            }
            .minOrNull()
    }

    private fun hasActivePairInTx(userId1: String, userId2: String): Boolean {
        val users = lockUsersInTx(listOf(userId1, userId2))
        return users.size == 2 && users.none { it[Users.deletedAt] != null }
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

    private fun hasBlockedPairInTx(userIds: List<String>): Boolean {
        val ids = userIds.distinct()
        if (ids.size < 2) return false
        return BlockedUsers.selectAll().where {
            (BlockedUsers.blockerId inList ids) and (BlockedUsers.blockedId inList ids)
        }.any()
    }

    private fun validateParticipantsInTx(userIds: List<String>): ConversationParticipantValidation {
        if (userIds.isEmpty()) {
            return ConversationParticipantValidation(ConversationCreationFailure.PARTICIPANTS_EMPTY)
        }
        val users = lockUsersInTx(userIds)
        val activeIds = users
            .filter { it[Users.deletedAt] == null }
            .mapTo(hashSetOf()) { it[Users.id] }
        val missingUserId = userIds.firstOrNull { it !in activeIds }
        if (missingUserId != null) {
            return ConversationParticipantValidation(
                ConversationCreationFailure.PARTICIPANT_NOT_FOUND,
                missingUserId,
            )
        }
        if (hasBlockedPairInTx(userIds)) {
            return ConversationParticipantValidation(ConversationCreationFailure.PARTICIPANT_BLOCKED)
        }
        return ConversationParticipantValidation()
    }

    private fun ConversationParticipantValidation.throwIfInvalid() {
        val failure = failure ?: return
        reject(failure, missingUserId)
    }

    private fun reject(failure: ConversationCreationFailure, userId: String? = null): Nothing {
        throw ConversationCreationRejected(failure, userId)
    }

    private fun isBlockedEitherWayInTx(userId1: String, userId2: String): Boolean {
        return BlockedUsers.selectAll().where {
            ((BlockedUsers.blockerId eq userId1) and (BlockedUsers.blockedId eq userId2)) or
                ((BlockedUsers.blockerId eq userId2) and (BlockedUsers.blockedId eq userId1))
        }.any()
    }

    private fun pairKey(userId1: String, userId2: String): String =
        listOf(userId1, userId2).sorted().joinToString(":")

    private fun isUniqueViolation(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty().lowercase()
            if (current is java.sql.SQLException && current.sqlState == "23505") return true
            if ("unique" in message || "duplicate key" in message) return true
            current = current.cause
        }
        return false
    }

    private companion object {
        const val ROLE_OWNER = "OWNER"
        const val ROLE_MEMBER = "MEMBER"
    }
}
