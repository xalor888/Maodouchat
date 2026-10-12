package com.maodouchat.server.service

import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import java.util.UUID

/**
 * On account deactivation: transfer group ownership when needed, remove membership,
 * and tear down empty chats. Returns attachment IDs removed with empty chats so callers
 * can delete disk objects; remaining uploader-owned attachments use deleteForUploader.
 */
internal fun AccountLifecycleService.removeUserFromAllChats(userId: String, now: Long): List<String> {
    val orphanedAttachmentIds = mutableListOf<String>()
    val memberships = ChatParticipants.selectAll()
        .where { ChatParticipants.userId eq userId }
        .orderBy(ChatParticipants.chatId to SortOrder.ASC)
        .map { it[ChatParticipants.chatId] to it[ChatParticipants.role] }
    if (memberships.isEmpty()) return orphanedAttachmentIds
    val chatIds = memberships.map { it.first }

    // 9.4xx 性能修复（N+1 → 2 次批量查询）：
    // 此前逐会话 SELECT chat + SELECT members（3N 次查询）；
    // 现一次锁全部 chat 行（保持 chat 先于 participants 的全局锁顺序），
    // 一次取全部会话的成员，内存中完成继任者计算，写入批量执行。
    val chatsById = Chats.selectAll()
        .where { Chats.id inList chatIds }
        .forUpdate()
        .associateBy { it[Chats.id] }
    val participantsByChat = ChatParticipants.selectAll()
        .where { ChatParticipants.chatId inList chatIds }
        .groupBy { it[ChatParticipants.chatId] }

    val successorUpdates = mutableListOf<Pair<String, String>>()      // chatId -> successorId
    val directPairCleanups = mutableListOf<String>()                  // 非空 1:1 chat
    val revisionBumps = mutableListOf<String>()                       // 非空群聊
    val auditRows = mutableListOf<Pair<String, Triple<String, String, String>>>() // chatId -> (action, actor, target)

    for ((chatId, role) in memberships) {
        val chat = chatsById[chatId] ?: continue
        deleteMessagingV2ParticipantStateInTx(chatId, userId)
        val others = participantsByChat[chatId].orEmpty()
            .filter { it[ChatParticipants.userId] != userId }
        if (chat[Chats.isGroup] && role == "OWNER" && others.isNotEmpty()) {
            val successor = others
                .sortedWith(
                    compareByDescending<ResultRow> {
                        when (it[ChatParticipants.role]) {
                            "ADMIN" -> 2
                            "MEMBER" -> 1
                            else -> 0
                        }
                    }.thenBy { it[ChatParticipants.joinedAt] }
                )
                .first()
            val successorId = successor[ChatParticipants.userId]
            successorUpdates += chatId to successorId
            auditRows += chatId to Triple("OWNERSHIP_TRANSFERRED", userId, successorId)
        }
        if (others.isEmpty()) {
            orphanedAttachmentIds += tearDownEmptyChat(chatId)
        } else if (!chat[Chats.isGroup]) {
            // 1:1 注销后 pair 映射失效，避免对方 getOrCreate 回落到半空 chat
            directPairCleanups += chatId
        } else {
            revisionBumps += chatId
            auditRows += chatId to Triple("MEMBER_LEFT", userId, userId)
        }
    }

    // 批量写
    successorUpdates.forEach { (chatId, successorId) ->
        ChatParticipants.update({
            (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq successorId)
        }) { it[ChatParticipants.role] = "OWNER" }
    }
    ChatUserSettings.deleteWhere {
        (ChatUserSettings.chatId inList chatIds) and (ChatUserSettings.userId eq userId)
    }
    ChatParticipants.deleteWhere {
        (ChatParticipants.chatId inList chatIds) and (ChatParticipants.userId eq userId)
    }
    if (directPairCleanups.isNotEmpty()) {
        DirectChatPairs.deleteWhere { DirectChatPairs.chatId inList directPairCleanups }
        SecretChatPairs.deleteWhere { SecretChatPairs.chatId inList directPairCleanups }
    }
    revisionBumps.forEach { chatId ->
        val latest = chatsById[chatId]
        if (latest != null) {
            Chats.update({ Chats.id eq chatId }) {
                it[Chats.memberRevision] = latest[Chats.memberRevision] + 1
            }
        }
    }
    auditRows.forEach { (chatId, triple) ->
        GroupAuditLogs.insert {
            it[GroupAuditLogs.id] = "gal_${UUID.randomUUID()}"
            it[GroupAuditLogs.chatId] = chatId
            it[GroupAuditLogs.action] = triple.first
            it[GroupAuditLogs.actorId] = triple.second
            it[GroupAuditLogs.targetUserId] = triple.third
            it[GroupAuditLogs.createdAt] = now
        }
    }
    return orphanedAttachmentIds
}

/** Deletes all chat-owned rows; returns attachment IDs that still need file cleanup. */
internal fun AccountLifecycleService.tearDownEmptyChat(chatId: String): List<String> {
    deleteMessagingV2ConversationInTx(chatId)
    val attachmentIds = EncryptedAttachments
        .select(EncryptedAttachments.id)
        .where { EncryptedAttachments.chatId eq chatId }
        .orderBy(EncryptedAttachments.id to SortOrder.ASC)
        .forUpdate()
        .map { it[EncryptedAttachments.id] }
    if (attachmentIds.isNotEmpty()) {
        EncryptedAttachments.deleteWhere { EncryptedAttachments.id inList attachmentIds }
    }
    val pollIds = GroupPolls.select(GroupPolls.id)
        .where { GroupPolls.chatId eq chatId }
        .orderBy(GroupPolls.id to SortOrder.ASC)
        .forUpdate()
        .map { it[GroupPolls.id] }
    if (pollIds.isNotEmpty()) {
        GroupPollVotes.deleteWhere { GroupPollVotes.pollId inList pollIds }
        GroupPolls.deleteWhere { GroupPolls.id inList pollIds }
    }
    // Keep account deletion aligned with ConversationStateDeletion's group-play cleanup set.
    // 残留（此前最后成员注销/清空群时留下群玩法孤儿行）
    GroupCheckins.deleteWhere { GroupCheckins.chatId eq chatId }
    val chainIds = GroupChains.select(GroupChains.id)
        .where { GroupChains.chatId eq chatId }
        .map { it[GroupChains.id] }
    if (chainIds.isNotEmpty()) {
        GroupChainEntries.deleteWhere { GroupChainEntries.chainId inList chainIds }
    }
    GroupChains.deleteWhere { GroupChains.chatId eq chatId }
    val pkIds = GroupPkRounds.select(GroupPkRounds.id)
        .where { GroupPkRounds.chatId eq chatId }
        .map { it[GroupPkRounds.id] }
    if (pkIds.isNotEmpty()) {
        GroupPkVotes.deleteWhere { GroupPkVotes.pkId inList pkIds }
    }
    GroupPkRounds.deleteWhere { GroupPkRounds.chatId eq chatId }
    BotCommandLogs.deleteWhere { BotCommandLogs.chatId eq chatId }
    // FK: direct_chat_pairs / secret_chat_pairs -> chats.id
    DirectChatPairs.deleteWhere { DirectChatPairs.chatId eq chatId }
    SecretChatPairs.deleteWhere { SecretChatPairs.chatId eq chatId }
    ChatUserSettings.deleteWhere { ChatUserSettings.chatId eq chatId }
    GroupAuditLogs.deleteWhere { GroupAuditLogs.chatId eq chatId }
    ChatParticipants.deleteWhere { ChatParticipants.chatId eq chatId }
    Chats.deleteWhere { Chats.id eq chatId }
    return attachmentIds
}
