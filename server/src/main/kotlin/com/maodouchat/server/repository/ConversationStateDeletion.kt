package com.maodouchat.server.repository

import com.maodouchat.server.db.BotCommandLogs
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.ChatUserSettings
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.DirectChatPairs
import com.maodouchat.server.db.EncryptedAttachments
import com.maodouchat.server.db.GroupAuditLogs
import com.maodouchat.server.db.GroupChainEntries
import com.maodouchat.server.db.GroupChains
import com.maodouchat.server.db.GroupCheckins
import com.maodouchat.server.db.GroupInvitations
import com.maodouchat.server.db.GroupPkRounds
import com.maodouchat.server.db.GroupPkVotes
import com.maodouchat.server.db.GroupPollVotes
import com.maodouchat.server.db.GroupPolls
import com.maodouchat.server.db.SecretChatPairs
import com.maodouchat.server.db.deleteMessagingV2ConversationInTx
import com.maodouchat.server.db.deleteMessagingV2ParticipantStateInTx
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.select

/** Transaction-local deletion rules shared by every membership lifecycle path. */
internal object ConversationStateDeletion {
    fun deleteParticipantState(chatId: String, userId: String) {
        deleteMessagingV2ParticipantStateInTx(chatId, userId)
        GroupCheckins.deleteWhere {
            (GroupCheckins.chatId eq chatId) and (GroupCheckins.userId eq userId)
        }
        val chainIds = chainIds(chatId)
        if (chainIds.isNotEmpty()) {
            GroupChainEntries.deleteWhere {
                (GroupChainEntries.chainId inList chainIds) and
                    (GroupChainEntries.userId eq userId)
            }
        }
        val pkIds = pkIds(chatId)
        if (pkIds.isNotEmpty()) {
            GroupPkVotes.deleteWhere {
                (GroupPkVotes.pkId inList pkIds) and
                    (GroupPkVotes.userId eq userId)
            }
        }
        val pollIds = pollIds(chatId)
        if (pollIds.isNotEmpty()) {
            GroupPollVotes.deleteWhere {
                (GroupPollVotes.pollId inList pollIds) and
                    (GroupPollVotes.userId eq userId)
            }
        }
    }

    /** Returns attachment ids whose encrypted files must be deleted after commit. */
    fun deleteConversation(chatId: String): List<String> {
        deleteMessagingV2ConversationInTx(chatId)
        val attachmentIds = EncryptedAttachments
            .select(EncryptedAttachments.id)
            .where { EncryptedAttachments.chatId eq chatId }
            .orderBy(EncryptedAttachments.id, SortOrder.ASC)
            .forUpdate()
            .map { it[EncryptedAttachments.id] }
        if (attachmentIds.isNotEmpty()) {
            EncryptedAttachments.deleteWhere { EncryptedAttachments.id inList attachmentIds }
        }
        val pollIds = pollIds(chatId)
        if (pollIds.isNotEmpty()) {
            GroupPollVotes.deleteWhere { GroupPollVotes.pollId inList pollIds }
            GroupPolls.deleteWhere { GroupPolls.id inList pollIds }
        }
        BotCommandLogs.deleteWhere { BotCommandLogs.chatId eq chatId }
        GroupInvitations.deleteWhere { GroupInvitations.chatId eq chatId }
        DirectChatPairs.deleteWhere { DirectChatPairs.chatId eq chatId }
        SecretChatPairs.deleteWhere { SecretChatPairs.chatId eq chatId }
        ChatUserSettings.deleteWhere { ChatUserSettings.chatId eq chatId }
        GroupAuditLogs.deleteWhere { GroupAuditLogs.chatId eq chatId }
        val chainIds = chainIds(chatId)
        if (chainIds.isNotEmpty()) {
            GroupChainEntries.deleteWhere { GroupChainEntries.chainId inList chainIds }
        }
        GroupChains.deleteWhere { GroupChains.chatId eq chatId }
        val pkIds = pkIds(chatId)
        if (pkIds.isNotEmpty()) {
            GroupPkVotes.deleteWhere { GroupPkVotes.pkId inList pkIds }
        }
        GroupPkRounds.deleteWhere { GroupPkRounds.chatId eq chatId }
        GroupCheckins.deleteWhere { GroupCheckins.chatId eq chatId }
        ChatParticipants.deleteWhere { ChatParticipants.chatId eq chatId }
        Chats.deleteWhere { Chats.id eq chatId }
        return attachmentIds
    }

    private fun chainIds(chatId: String): List<String> = GroupChains
        .select(GroupChains.id)
        .where { GroupChains.chatId eq chatId }
        .map { it[GroupChains.id] }

    private fun pkIds(chatId: String): List<String> = GroupPkRounds
        .select(GroupPkRounds.id)
        .where { GroupPkRounds.chatId eq chatId }
        .map { it[GroupPkRounds.id] }

    private fun pollIds(chatId: String): List<String> = GroupPolls
        .select(GroupPolls.id)
        .where { GroupPolls.chatId eq chatId }
        .map { it[GroupPolls.id] }
}
