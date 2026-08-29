package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.ChatUserSettings
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.DirectChatPairs
import com.maodouchat.server.db.SecretChatPairs
import com.maodouchat.server.model.ChatType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** Owns participant departure and whole-conversation teardown. */
class ConversationLifecycleRepository {
    fun leave(
        chatId: String,
        userId: String,
        requireBotDeliverable: Boolean = false,
    ): LeaveConversationOutcome = transaction {
        if (requireBotDeliverable && !BotRepository.isBotDeliverableInTx(userId, System.currentTimeMillis())) {
            return@transaction LeaveConversationOutcome(LeaveConversationResult.NOT_PARTICIPANT)
        }
        val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
            ?: return@transaction LeaveConversationOutcome(LeaveConversationResult.NOT_PARTICIPANT)
        val participants = ChatParticipants.selectAll()
            .where { ChatParticipants.chatId eq chatId }
            .toList()
        val current = participants.firstOrNull { it[ChatParticipants.userId] == userId }
            ?: return@transaction LeaveConversationOutcome(LeaveConversationResult.NOT_PARTICIPANT)
        val wasGroup = chat[Chats.isGroup]
        val recipientsBefore = if (wasGroup) {
            participants.map { it[ChatParticipants.userId] }
        } else {
            emptyList()
        }
        val revisionAfter = if (wasGroup) chat[Chats.memberRevision] + 1 else null

        if (chat[Chats.chatType] == ChatType.CHANNEL && current[ChatParticipants.role] == ROLE_OWNER) {
            participants.asSequence()
                .map { it[ChatParticipants.userId] }
                .filterNot { it == userId }
                .forEach { subscriberId ->
                    GroupMutationTransaction.insertAudit(chatId, userId, "MEMBER_LEFT", subscriberId)
                }
            Chats.update({ Chats.id eq chatId }) {
                it[memberRevision] = revisionAfter!!
            }
            ChatUserSettings.deleteWhere {
                (ChatUserSettings.chatId eq chatId) and (ChatUserSettings.userId eq userId)
            }
            val attachments = ConversationStateDeletion.deleteConversation(chatId)
            return@transaction LeaveConversationOutcome(
                result = LeaveConversationResult.LEFT,
                wasGroup = true,
                recipientsBefore = recipientsBefore,
                memberRevisionAfter = revisionAfter,
                conversationDeleted = true,
                deletedAttachmentIds = attachments,
                deletedGroupAvatarUrl = chat[Chats.groupAvatar],
            )
        }
        if (wasGroup && current[ChatParticipants.role] == ROLE_OWNER && participants.size > 1) {
            return@transaction LeaveConversationOutcome(
                result = LeaveConversationResult.OWNER_TRANSFER_REQUIRED,
                wasGroup = true,
                recipientsBefore = recipientsBefore,
                memberRevisionAfter = chat[Chats.memberRevision],
            )
        }

        ChatUserSettings.deleteWhere {
            (ChatUserSettings.chatId eq chatId) and (ChatUserSettings.userId eq userId)
        }
        if (wasGroup) ConversationStateDeletion.deleteParticipantState(chatId, userId)
        val deleted = ChatParticipants.deleteWhere {
            (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId)
        }
        val remaining = ChatParticipants.selectAll().where { ChatParticipants.chatId eq chatId }.count()
        if (remaining == 0L || (!wasGroup && remaining == 1L)) {
            val attachments = ConversationStateDeletion.deleteConversation(chatId)
            return@transaction LeaveConversationOutcome(
                result = LeaveConversationResult.LEFT,
                wasGroup = wasGroup,
                recipientsBefore = recipientsBefore,
                memberRevisionAfter = revisionAfter,
                conversationDeleted = true,
                deletedAttachmentIds = attachments,
                deletedGroupAvatarUrl = chat[Chats.groupAvatar].takeIf { wasGroup },
            )
        }
        if (!wasGroup) {
            DirectChatPairs.deleteWhere { DirectChatPairs.chatId eq chatId }
            SecretChatPairs.deleteWhere { SecretChatPairs.chatId eq chatId }
        } else if (deleted > 0) {
            Chats.update({ Chats.id eq chatId }) {
                it[memberRevision] = revisionAfter!!
            }
            GroupMutationTransaction.insertAudit(chatId, userId, "MEMBER_LEFT", userId)
        }
        LeaveConversationOutcome(
            result = LeaveConversationResult.LEFT,
            wasGroup = wasGroup,
            recipientsBefore = recipientsBefore,
            memberRevisionAfter = revisionAfter,
        )
    }

    private companion object {
        const val ROLE_OWNER = "OWNER"
    }
}
