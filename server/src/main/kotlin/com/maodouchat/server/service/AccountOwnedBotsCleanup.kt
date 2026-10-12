package com.maodouchat.server.service

import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import java.util.UUID

/** Must run after locking the owner Users row. */
internal fun AccountLifecycleService.removeOwnedBots(ownerUserId: String, now: Long): List<String> {
    val ownedBots = BotApps.selectAll()
        .where { BotApps.ownerUserId eq ownerUserId }
        .orderBy(BotApps.id to SortOrder.ASC)
        .forUpdate()
        .toList()
    if (ownedBots.isEmpty()) return emptyList()
    val botIds = ownedBots.map { it[BotApps.id] }
    val botIdSet = botIds.toSet()
    val chatIds = ChatParticipants.select(ChatParticipants.chatId)
        .where { ChatParticipants.userId inList botIds }
        .map { it[ChatParticipants.chatId] }
        .distinct()
        .sorted()
    val lockedChats: List<ResultRow> = if (chatIds.isEmpty()) emptyList() else Chats.selectAll()
        .where { Chats.id inList chatIds }
        .orderBy(Chats.id to SortOrder.ASC)
        .forUpdate()
        .toList()

    lockedChats.forEach { chat ->
        if (!chat[Chats.isGroup]) return@forEach
        val chatId = chat[Chats.id]
        val participants = ChatParticipants.selectAll()
            .where { ChatParticipants.chatId eq chatId }
            .toList()
        val deletingOwner = participants.any {
            it[ChatParticipants.userId] in botIdSet && it[ChatParticipants.role] == "OWNER"
        }
        if (deletingOwner) {
            val successor = participants
                .filterNot { it[ChatParticipants.userId] in botIdSet }
                .sortedWith(
                    compareByDescending<ResultRow> {
                        when (it[ChatParticipants.role]) {
                            "ADMIN" -> 2
                            "MEMBER" -> 1
                            else -> 0
                        }
                    }.thenBy { it[ChatParticipants.joinedAt] }
                        .thenBy { it[ChatParticipants.userId] }
                )
                .firstOrNull()
            if (successor != null) {
                val successorId = successor[ChatParticipants.userId]
                ChatParticipants.update({
                    (ChatParticipants.chatId eq chatId) and
                        (ChatParticipants.userId eq successorId)
                }) { it[ChatParticipants.role] = "OWNER" }
                GroupAuditLogs.insert {
                    it[GroupAuditLogs.id] = "gal_${UUID.randomUUID()}"
                    it[GroupAuditLogs.chatId] = chatId
                    it[GroupAuditLogs.actorId] = ownerUserId
                    it[GroupAuditLogs.action] = "OWNERSHIP_TRANSFERRED"
                    it[GroupAuditLogs.targetUserId] = successorId
                    it[GroupAuditLogs.createdAt] = now
                }
            }
        }
    }

    lockedChats.forEach { chat ->
        botIds.forEach { botId ->
            deleteMessagingV2ParticipantStateInTx(chat[Chats.id], botId)
        }
    }
    ChatUserSettings.deleteWhere { ChatUserSettings.userId inList botIds }
    ChatParticipants.deleteWhere { ChatParticipants.userId inList botIds }
    val orphanedAttachmentIds = mutableListOf<String>()
    lockedChats.forEach { chat ->
        val chatId = chat[Chats.id]
        val remaining = ChatParticipants.select(ChatParticipants.userId)
            .where { ChatParticipants.chatId eq chatId }
            .limit(1)
            .any()
        if (!remaining) {
            orphanedAttachmentIds += tearDownEmptyChat(chatId)
        } else if (chat[Chats.isGroup]) {
            Chats.update({ Chats.id eq chatId }) {
                it[Chats.memberRevision] = chat[Chats.memberRevision] + 1
            }
        } else {
            DirectChatPairs.deleteWhere { DirectChatPairs.chatId eq chatId }
            SecretChatPairs.deleteWhere { SecretChatPairs.chatId eq chatId }
        }
    }

    deletePollsCreatedBy(botIds)
    GroupPollVotes.deleteWhere { GroupPollVotes.userId inList botIds }
    StarMessages.deleteWhere { StarMessages.userId inList botIds }
    botIds.forEach { botId ->
        BotCommandLogs.deleteWhere {
            (BotCommandLogs.botId eq botId) or (BotCommandLogs.userId eq botId)
        }
    }
    BotUpdateInbox.deleteWhere { BotUpdateInbox.botId inList botIds }
    BotApps.deleteWhere {
        (BotApps.ownerUserId eq ownerUserId) and (BotApps.id inList botIds)
    }
    Users.update({ Users.id inList botIds }) {
        it[Users.name] = "deleted-bot"
        it[Users.status] = ""
        it[Users.avatar] = null
        it[Users.isOnline] = false
        it[Users.showOnline] = false
        it[Users.showStatus] = false
        it[Users.searchable] = false
        it[Users.deletedAt] = now
        it[Users.lastSeen] = 0L
    }
    return orphanedAttachmentIds
}
