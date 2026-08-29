package com.maodouchat.server.repository

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.StarMessages
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
import com.maodouchat.server.model.ChatType
import com.maodouchat.server.model.StarredMessageReference
import java.sql.SQLException
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** Per-user message controls backed only by immutable v2 transport metadata. */
class StarMessageRepository {

    fun toggleStar(
        userId: String,
        messageId: String,
        requireBotDeliverable: Boolean = false,
    ): Boolean? = try {
        transaction {
            if (requireBotDeliverable && !BotRepository.isBotDeliverableInTx(userId, System.currentTimeMillis())) {
                return@transaction null
            }
            val message = MessagingV2Messages.selectAll()
                .where { MessagingV2Messages.id eq messageId }
                .forUpdate()
                .firstOrNull()
                ?: return@transaction null
            val chatId = message[MessagingV2Messages.conversationId]
            val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
                ?: return@transaction null
            val isMember = ChatParticipants.selectAll().where {
                (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId)
            }.firstOrNull() != null
            if (!isMember || chat[Chats.chatType] == ChatType.SECRET) return@transaction null

            val existing = StarMessages.selectAll().where {
                (StarMessages.userId eq userId) and (StarMessages.messageId eq messageId)
            }.forUpdate().firstOrNull()
            if (message[MessagingV2Messages.recordClass] != MessagingV2RecordClass.MESSAGE) {
                if (existing == null) return@transaction null
                StarMessages.deleteWhere {
                    (StarMessages.userId eq userId) and (StarMessages.messageId eq messageId)
                }
                return@transaction false
            }
            if (existing != null) {
                StarMessages.deleteWhere {
                    (StarMessages.userId eq userId) and (StarMessages.messageId eq messageId)
                }
                false
            } else {
                StarMessages.insert {
                    it[StarMessages.userId] = userId
                    it[StarMessages.messageId] = messageId
                    it[StarMessages.starredAt] = System.currentTimeMillis()
                }
                true
            }
        }
    } catch (error: Exception) {
        if (!isUniqueViolation(error)) throw error
        transaction {
            StarMessages.selectAll().where {
                (StarMessages.userId eq userId) and (StarMessages.messageId eq messageId)
            }.firstOrNull() != null
        }
    }

    /** Returns identifiers only. Decrypted bodies remain in each authorized device's Room database. */
    fun getStarredMessages(userId: String, chatId: String? = null): List<StarredMessageReference> = transaction {
        val starredRows = StarMessages.selectAll()
            .where { StarMessages.userId eq userId }
            .orderBy(StarMessages.starredAt to SortOrder.DESC)
            .limit(MAX_STARRED_RETURN)
            .map { it[StarMessages.messageId] to it[StarMessages.starredAt] }
        if (starredRows.isEmpty()) return@transaction emptyList()

        val blockedByMe = BlockedUsers.selectAll()
            .where { BlockedUsers.blockerId eq userId }
            .mapTo(linkedSetOf()) { it[BlockedUsers.blockedId] }
        val blockedMe = BlockedUsers.selectAll()
            .where { BlockedUsers.blockedId eq userId }
            .mapTo(linkedSetOf()) { it[BlockedUsers.blockerId] }
        val blockedSenders = blockedByMe + blockedMe
        val secretChatIds = Chats.select(Chats.id)
            .where { Chats.chatType eq ChatType.SECRET }
            .mapTo(linkedSetOf()) { it[Chats.id] }
        if (chatId != null && chatId in secretChatIds) return@transaction emptyList()

        val ids = starredRows.map { it.first }
        var condition = (MessagingV2Messages.id inList ids) and
            (MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE) and
            (ChatParticipants.userId eq userId)
        if (chatId != null) condition = condition and (MessagingV2Messages.conversationId eq chatId)
        if (secretChatIds.isNotEmpty()) {
            condition = condition and (MessagingV2Messages.conversationId notInList secretChatIds.toList())
        }
        if (blockedSenders.isNotEmpty()) {
            condition = condition and (MessagingV2Messages.senderUserId notInList blockedSenders.toList())
        }
        val membershipJoin = MessagingV2Messages.innerJoin(
            ChatParticipants,
            { MessagingV2Messages.conversationId },
            { ChatParticipants.chatId },
        )
        val metadataById = membershipJoin.selectAll().where { condition }.associate { row ->
            row[MessagingV2Messages.id] to row[MessagingV2Messages.conversationId]
        }
        starredRows.mapNotNull { (messageId, starredAt) ->
            metadataById[messageId]?.let { conversationId ->
                StarredMessageReference(messageId, conversationId, starredAt)
            }
        }
    }

    companion object {
        const val MAX_STARRED_RETURN = 1000

        private fun isUniqueViolation(error: Throwable): Boolean {
            var current: Throwable? = error
            while (current != null) {
                val message = current.message.orEmpty().lowercase()
                if (current is SQLException && current.sqlState == "23505") return true
                if (message.contains("unique") || message.contains("duplicate key")) return true
                current = current.cause
            }
            return false
        }
    }
}
